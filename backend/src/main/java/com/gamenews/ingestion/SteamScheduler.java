package com.gamenews.ingestion;

import com.gamenews.auth.ApiException;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ingestion.steam", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SteamScheduler {
    private final SteamIngestionService service;
    private final SteamProperties properties;
    public SteamScheduler(SteamIngestionService service, SteamProperties properties) { this.service = service; this.properties = properties; }
    @Scheduled(fixedDelayString = "${ingestion.steam.interval-ms:1800000}", initialDelayString = "${ingestion.steam.initial-delay-ms:10000}")
    public void collect() {
        if (!properties.isEnabled()) return;
        try {
            var result = service.collect(IngestionRun.Trigger.SCHEDULED);
            LoggerFactory.getLogger(getClass()).info("Steam 예약 수집 완료: 실행 {}, 상태 {}, 저장 {}, 중복 {}, 실패 {}", result.id(), result.status(), result.savedCount(), result.duplicateCount(), result.failedCount());
        } catch (ApiException error) {
            LoggerFactory.getLogger(getClass()).info("Steam 예약 수집 건너뜀: {}", error.code());
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(getClass()).warn("Steam 예약 수집 실행 실패: {}", error.getClass().getSimpleName());
        }
    }
}
