package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.*;
import com.gamenews.articles.*;
import com.gamenews.auth.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ingestion.steam.enabled=true", "ingestion.steam.scheduling-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:ingestion-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestionIntegrationTest {
    @MockitoBean SteamGateway client;
    @Autowired SteamIngestionService service;
    @Autowired SteamCatalog catalog;
    @Autowired IngestionRunRepository runs;
    @Autowired ArticleRepository articles;
    @Autowired SourceRepository sources;
    @Autowired GameRepository games;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired IngestionLease lease;
    @Autowired JdbcTemplate jdbc;
    @Autowired SteamProperties properties;

    @BeforeEach void setup() throws Exception {
        clean(); catalog.prepare();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return fixture(call.getArgument(0));
        }).when(client).fetch(anyLong());
    }
    void clean() { runs.deleteAll(); articles.deleteAll(); games.deleteAll(); sources.deleteAll(); users.deleteAll(); }
    @AfterEach void cleanup() { properties.setEnabled(true); clean(); }
    List<JsonNode> fixture(long appId) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/steam/" + appId + ".json")) {
            var items = new ArrayList<JsonNode>(); json.readTree(input).path("appnews").path("newsitems").forEach(items::add); return items;
        }
    }
    String token(UserAccount.Role role) throws Exception {
        users.deleteAll(); users.saveAndFlush(new UserAccount("operator@example.com", passwords.encode("test-password-123"), role));
        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"operator@example.com\",\"password\":\"test-password-123\"}"))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @Test void 다섯게임25건을정제해자동공개하고반복수집시중복만기록한다() {
        var first = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(first.status()).isEqualTo(IngestionRun.Status.SUCCESS);
        assertThat(first.fetchedCount()).isEqualTo(25); assertThat(first.savedCount()).isEqualTo(25); assertThat(first.failedCount()).isZero();
        var second = service.collect(IngestionRun.Trigger.SCHEDULED);
        assertThat(second.savedCount()).isZero(); assertThat(second.duplicateCount()).isEqualTo(25);
        assertThat(articles.count()).isEqualTo(25); assertThat(runs.count()).isEqualTo(2);
        assertThat(articles.findAll()).allMatch(article -> article.getStatus() == Article.Status.PUBLISHED && !article.isDemo());
        assertThat(articles.findAll()).anyMatch(article -> article.getThumbnailUrl() != null);
    }
    @Test void 숨김과수정된게임분류를재수집이덮어쓰지않는다() throws Exception {
        service.collect(IngestionRun.Trigger.MANUAL);
        var source = sources.findByCode("steam").orElseThrow();
        String gid = fixture(570).getFirst().path("gid").asText();
        var original = articles.findBySourceIdAndExternalId(source.getId(), gid).orElseThrow();
        var correctedGame = catalog.game(730);
        original.setStatus(Article.Status.HIDDEN); original.setGame(correctedGame); original = articles.saveAndFlush(original);
        long version = original.getVersion();
        var result = service.collect(IngestionRun.Trigger.MANUAL);
        var preserved = articles.findById(original.getId()).orElseThrow();
        assertThat(preserved.getStatus()).isEqualTo(Article.Status.HIDDEN);
        assertThat(preserved.getGame().getId()).isEqualTo(correctedGame.getId());
        assertThat(preserved.getVersion()).isEqualTo(version);
        assertThat(result.savedCount()).isZero(); assertThat(result.duplicateCount()).isEqualTo(25);
    }
    @Test void 일부항목오류와게임통신오류가다른게임저장을막지않는다() throws Exception {
        var malformed = fixture(570);
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed.getFirst()).remove("title");
        when(client.fetch(570)).thenReturn(malformed);
        when(client.fetch(730)).thenThrow(new SteamNewsClient.FetchException("Steam HTTP 503"));
        var result = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(result.status()).isEqualTo(IngestionRun.Status.PARTIAL);
        assertThat(result.fetchedCount()).isEqualTo(20); assertThat(result.savedCount()).isEqualTo(19);
        assertThat(result.failedCount()).isEqualTo(2); assertThat(result.errorSummary()).contains("570", "730", "503");
    }
    @Test void 모든게임실패도종료시각과실패이력을남긴다() {
        doThrow(new SteamNewsClient.FetchException("Steam 요청 시간이 초과되었습니다.")).when(client).fetch(anyLong());
        var result = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(result.status()).isEqualTo(IngestionRun.Status.FAILED); assertThat(result.failedCount()).isEqualTo(5);
        assertThat(result.finishedAt()).isNotNull(); assertThat(articles.count()).isZero(); assertThat(runs.count()).isEqualTo(1);
    }
    @Test void 동시실행은한번만허용하고뒤요청을409로거절한다() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return fixture(call.getArgument(0)); }).when(client).fetch(anyLong());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> service.collect(IngestionRun.Trigger.SCHEDULED));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.collect(IngestionRun.Trigger.MANUAL)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status().value()).isEqualTo(409));
                assertThat(runs.count()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(active.get(10, TimeUnit.SECONDS).savedCount()).isEqualTo(25);
        }
    }
    @Test void 만료된실행권을회수하고중단된이력을실패로종결한다() {
        var source = catalog.prepare(); assertThat(lease.acquire(source.getId(), "old-owner")).isTrue();
        var interrupted = runs.saveAndFlush(new IngestionRun(source, IngestionRun.Trigger.SCHEDULED));
        jdbc.update("UPDATE sources SET ingestion_lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), source.getId());
        var result = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(result.status()).isEqualTo(IngestionRun.Status.SUCCESS);
        assertThat(runs.findById(interrupted.getId()).orElseThrow().getStatus()).isEqualTo(IngestionRun.Status.FAILED);
    }
    @Test void 비활성출처와수집비활성설정은외부요청을하지않는다() {
        var source = catalog.prepare(); source.setActive(false); sources.saveAndFlush(source);
        assertThatThrownBy(() -> service.collect(IngestionRun.Trigger.MANUAL)).isInstanceOf(ApiException.class);
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.collect(IngestionRun.Trigger.MANUAL)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client); assertThat(runs.count()).isZero();
    }
    @Test void 관리자만수집과이력조회가가능하고요청마다현재상태를검사한다() throws Exception {
        for (String method : List.of("GET", "POST")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/admin/ingestions")).andExpect(status().isUnauthorized());
        }
        String regular = token(UserAccount.Role.USER);
        mvc.perform(post("/api/admin/ingestions").header("Authorization", "Bearer " + regular)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ingestions").header("Authorization", "Bearer " + regular)).andExpect(status().isForbidden());
        String admin = token(UserAccount.Role.ADMIN);
        mvc.perform(post("/api/admin/ingestions").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(25)).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/admin/ingestions").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].savedCount").value(25));
        mvc.perform(get("/api/admin/ingestions?size=51").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        var suspended = users.findByEmail("operator@example.com").orElseThrow(); suspended.suspend("운영 정책 위반"); users.saveAndFlush(suspended);
        mvc.perform(post("/api/admin/ingestions").header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden());
    }
    @Test void 뉴스버전충돌은기존관리자상태를보호한다() {
        service.collect(IngestionRun.Trigger.MANUAL);
        Long id = articles.findAll().getFirst().getId();
        var first = articles.findById(id).orElseThrow(); var second = articles.findById(id).orElseThrow();
        first.setStatus(Article.Status.HIDDEN); articles.saveAndFlush(first);
        second.setGame(catalog.game(570));
        assertThatThrownBy(() -> articles.saveAndFlush(second)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }
}
