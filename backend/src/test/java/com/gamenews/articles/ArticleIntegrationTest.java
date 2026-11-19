package com.gamenews.articles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamenews.auth.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ArticleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ArticleStore store;
    @Autowired ArticleRepository articles;
    @Autowired GameRepository games;
    @Autowired SourceRepository sources;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired jakarta.validation.Validator validator;
    private Source source;
    private Game game;
    private String token;
    private final Instant publishedAt = Instant.parse("2026-11-17T03:00:00Z");

    @BeforeEach void prepare() throws Exception {
        cleanArticles(); users.deleteAll();
        source = sources.saveAndFlush(new Source("test-source", "테스트 출처"));
        game = games.saveAndFlush(new Game("테스트 게임", 570L));
        users.saveAndFlush(new UserAccount("reader@example.com", encoder.encode("test-password-123"), UserAccount.Role.USER));
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", "reader@example.com", "password", "test-password-123"))))
            .andExpect(status().isOk()).andReturn();
        token = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    void cleanArticles() { articles.deleteAll(); games.deleteAll(); sources.deleteAll(); }
    @AfterEach void cleanup() { cleanArticles(); }

    ArticleStore.Draft draft(String externalId, String title, Long gameId, Instant published) {
        return new ArticleStore.Draft(source.getId(), gameId, externalId, title, "본문입니다.\n<script>alert('실행 금지')</script>",
            "https://example.com/news/" + externalId, published, false);
    }
    Long save(String externalId, String title) { return store.store(draft(externalId, title, game.getId(), publishedAt)).id(); }

    @Test void 저장한글을로그인후목록과상세에서조회한다() throws Exception {
        Long id = save("18446744073709551615", "저장한 글");
        mvc.perform(get("/api/articles").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.items[0].game.name").value("테스트 게임"))
            .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("저장한 글"))
            .andExpect(jsonPath("$.source.name").value("테스트 출처"))
            .andExpect(jsonPath("$.body").value("본문입니다.\n<script>alert('실행 금지')</script>"));
        assertThat(articles.findById(id).orElseThrow().getExternalId()).isEqualTo("18446744073709551615");
    }

    @Test void 비회원은뉴스와게임을조회할수있고쓰기관리요청은거부된다() throws Exception {
        Long id = save("public", "공개 뉴스");
        mvc.perform(get("/api/articles"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/articles/" + id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("공개 뉴스"));
        mvc.perform(get("/api/games"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].steamAppId").value(570));
        mvc.perform(get("/api/games/steam").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/ingestions")).andExpect(status().isUnauthorized());
        for (String path : List.of("/api/articles", "/api/articles/" + id, "/api/articles/" + id + "/comments", "/api/articles/" + id + "/likes", "/api/reports")) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test void 숨긴글은목록과직접주소에서노출하지않는다() throws Exception {
        Long id = save("hidden", "숨김 비밀 제목");
        mvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        var hidden = articles.findById(id).orElseThrow(); hidden.setStatus(Article.Status.HIDDEN); articles.saveAndFlush(hidden);
        mvc.perform(get("/api/articles").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        mvc.perform(get("/api/articles")).andExpect(jsonPath("$.items").isEmpty());
        for (Long unavailable : List.of(id, 999999L)) {
            mvc.perform(get("/api/articles/" + unavailable))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        }
    }

    @Test void 페이지정렬게임필터검색과미분류를일관되게적용한다() throws Exception {
        Long first = save("first", "첫 글");
        Long second = save("second", "Dota 업데이트");
        Long unclassified = store.store(draft("unclassified", "미분류 글", null, publishedAt.minusSeconds(60))).id();
        mvc.perform(get("/api/articles").param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.items[0].id").value(second)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/articles").param("size", "1").param("page", "1").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.items[0].id").value(first));
        mvc.perform(get("/api/articles").param("q", "  DOTA  ").param("gameId", game.getId().toString()).header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.items[0].id").value(second)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/articles").param("gameId", game.getId().toString()))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/articles").param("steamAppId", "570").param("q", "Dota"))
            .andExpect(jsonPath("$.items[0].id").value(second)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/articles").param("steamAppId", "105600"))
            .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/articles").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.items[2].id").value(unclassified)).andExpect(jsonPath("$.items[2].game").isEmpty());
        mvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$[0].steamAppId").value(570));
    }

    @ParameterizedTest @ValueSource(strings = {"%", "_", "!"})
    void 검색의특수문자를와일드카드로해석하지않는다(String marker) throws Exception {
        save("special", "특수문자 " + marker); save("ordinary", "일반 제목");
        mvc.perform(get("/api/articles").param("q", marker).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test void 잘못된조회인자는400이고빈검색은정상응답이다() throws Exception {
        for (String params : List.of("page=-1", "size=0", "size=51", "gameId=-1", "gameId=abc", "page=abc", "steamAppId=4294967296", "q=" + "a".repeat(101))) {
            mvc.perform(get("/api/articles?" + params)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/articles/abc").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/articles").param("q", "없는제목").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void 중복저장은숨김상태와원래내용을덮어쓰지않는다() {
        Long id = save("duplicate", "원래 제목");
        var hidden = articles.findById(id).orElseThrow(); hidden.setStatus(Article.Status.HIDDEN); articles.saveAndFlush(hidden);
        var again = store.store(draft("duplicate", "변경된 제목", null, publishedAt));
        assertThat(again.id()).isEqualTo(id); assertThat(again.created()).isFalse();
        var preserved = articles.findById(id).orElseThrow();
        assertThat(preserved.getTitle()).isEqualTo("원래 제목");
        assertThat(preserved.getStatus()).isEqualTo(Article.Status.HIDDEN);
        assertThat(articles.count()).isEqualTo(1);
    }

    @Test void 동시저장도한건만생성한다() throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ArticleStore.Stored> task = () -> { ready.countDown(); start.await(); return store.store(draft("concurrent", "동시 저장", game.getId(), publishedAt)); };
            var one = executor.submit(task); var two = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var first = one.get(10, TimeUnit.SECONDS); var second = two.get(10, TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(List.of(first.created(), second.created())).containsExactlyInAnyOrder(true, false);
            assertThat(articles.count()).isEqualTo(1);
        }
    }

    @Test void 잘못된원문주소출처본문은저장하지않는다() {
        for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.com", "깨진 주소")) {
            var invalid = new ArticleStore.Draft(source.getId(), game.getId(), "invalid", "제목", "본문", url, publishedAt, false);
            assertThatThrownBy(() -> store.store(invalid)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> store.store(draft("unknown", "제목", 999999L, publishedAt))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store.store(draft("blank", "  ", game.getId(), publishedAt))).isInstanceOf(ApiException.class);
        assertThat(articles.count()).isZero();
    }

    @Test void 정지전토큰으로글을조회할수없다() throws Exception {
        var user = users.findByEmail("reader@example.com").orElseThrow(); user.suspend("테스트 정지 사유"); users.saveAndFlush(user);
        mvc.perform(get("/api/articles").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
}
