package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = {com.gamenews.GameNewsApplication.class, SteamSchedulerIntegrationTest.GatewayFixture.class}, properties = {
    "ingestion.steam.enabled=true", "ingestion.steam.scheduling-enabled=true", "ingestion.steam.initial-delay-ms=200",
    "ingestion.steam.interval-ms=3600000", "spring.datasource.url=jdbc:h2:mem:scheduler-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@DirtiesContext
class SteamSchedulerIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayFixture {
        @Bean @Primary SteamGateway scheduledFixture(ObjectMapper json) {
            return appId -> {
                var item = json.createObjectNode();
                item.put("appid", appId).put("gid", appId + "123").put("title", "예약 수집 확인")
                    .put("contents", "[b]예약 실행[/b]으로 저장한 본문입니다.").put("date", Instant.now().getEpochSecond())
                    .put("feedname", "steam_community_announcements").put("url", "https://store.steampowered.com/news/app/" + appId);
                return List.of(item);
            };
        }
    }
    @Autowired SteamIngestionService service;
    @Test void 수동호출없이예약작업이실행되어뉴스와이력을저장한다() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var history = service.history(0, 10);
            assertThat(history.items()).hasSize(1);
            var run = history.items().getFirst();
            assertThat(run.trigger()).isEqualTo(IngestionRun.Trigger.SCHEDULED);
            assertThat(run.status()).isEqualTo(IngestionRun.Status.SUCCESS);
            assertThat(run.savedCount()).isEqualTo(5);
        });
    }
}
