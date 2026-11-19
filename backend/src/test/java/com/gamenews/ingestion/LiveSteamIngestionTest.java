package com.gamenews.ingestion;

import com.gamenews.articles.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "STEAM_LIVE_TEST", matches = "true")
@SpringBootTest(properties = {"ingestion.steam.enabled=true", "ingestion.steam.scheduling-enabled=false", "ingestion.steam.count=5",
    "spring.datasource.url=jdbc:h2:file:../.cache/steam-live-verification;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class LiveSteamIngestionTest {
    @Autowired SteamIngestionService service;
    @Autowired SteamCatalog catalog;
    @Autowired ArticleRepository articles;
    @Test void 실제Steam응답을파일DB에저장하고연속수집의중복을확인한다() {
        var first = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(first.status()).isEqualTo(IngestionRun.Status.SUCCESS);
        assertThat(first.fetchedCount()).isEqualTo(25); assertThat(first.failedCount()).isZero();
        for (var seed : SteamCatalog.GAMES) {
            var game = catalog.game(seed.appId());
            assertThat(articles.findPublicPage(Article.Status.PUBLISHED, game.getId(), "%", PageRequest.of(0, 10)).getTotalElements()).isGreaterThan(0);
        }
        var second = service.collect(IngestionRun.Trigger.MANUAL);
        assertThat(second.savedCount()).isZero(); assertThat(second.duplicateCount()).isEqualTo(25);
        System.out.println("실제 Steam 수집 검증 통과: 게임 5종, 수신 " + first.fetchedCount() + "건, 첫 실행 신규 " + first.savedCount() + "건, 두 번째 신규 " + second.savedCount() + "건, 중복 " + second.duplicateCount() + "건");
    }
}
