package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamenews.articles.*;
import com.gamenews.auth.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SteamIngestionService {
    public record RunView(Long id, String source, IngestionRun.Trigger trigger, IngestionRun.Status status,
                          Instant startedAt, Instant finishedAt, int fetchedCount, int savedCount,
                          int duplicateCount, int failedCount, String errorSummary) {
        static RunView from(IngestionRun run) {
            return new RunView(run.getId(), run.getSource().getName(), run.getTrigger(), run.getStatus(), run.getStartedAt(),
                run.getFinishedAt(), run.getFetchedCount(), run.getSavedCount(), run.getDuplicateCount(), run.getFailedCount(), run.getErrorSummary());
        }
    }
    public record RunPage(List<RunView> items, int page, int size, long totalElements, int totalPages,
                          boolean enabled, boolean schedulingEnabled, long intervalMs) {}
    private final SteamProperties properties;
    private final SteamCatalog catalog;
    private final SteamGateway client;
    private final SteamContent content;
    private final ArticleStore store;
    private final IngestionRunRepository runs;
    private final IngestionLease lease;
    public SteamIngestionService(SteamProperties properties, SteamCatalog catalog, SteamGateway client, SteamContent content,
                                 ArticleStore store, IngestionRunRepository runs, IngestionLease lease) {
        this.properties = properties; this.catalog = catalog; this.client = client; this.content = content;
        this.store = store; this.runs = runs; this.lease = lease;
    }

    // 외부 요청 전체를 감싸는 DB 트랜잭션을 열지 않는다.
    public RunView collect(IngestionRun.Trigger trigger) {
        if (!properties.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INGESTION_DISABLED", "Steam 수집이 비활성화되어 있습니다.");
        Source source = catalog.prepare();
        if (!source.isActive()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_DISABLED", "Steam 출처가 비활성화되어 있습니다.");
        String owner = UUID.randomUUID().toString();
        if (!lease.acquire(source.getId(), owner)) throw new ApiException(HttpStatus.CONFLICT, "INGESTION_RUNNING", "이미 Steam 뉴스를 수집 중입니다. 잠시 후 이력을 확인해 주세요.");
        try {
            runs.closeInterrupted(source.getId(), Instant.now(), "이전 실행이 종료되지 않아 실행권 만료 후 실패로 기록했습니다.", IngestionRun.Status.FAILED, IngestionRun.Status.RUNNING);
            IngestionRun run = runs.saveAndFlush(new IngestionRun(source, trigger));
            int fetched = 0, saved = 0, duplicates = 0, failed = 0, successfulGames = 0;
            var errors = new ArrayList<String>();
            for (var seed : SteamCatalog.GAMES) {
                try {
                    lease.renew(source.getId(), owner);
                    List<JsonNode> items = client.fetch(seed.appId());
                    lease.renew(source.getId(), owner);
                    successfulGames++; fetched += items.size();
                    var game = catalog.game(seed.appId());
                    for (int i = 0; i < items.size(); i++) {
                        lease.renew(source.getId(), owner);
                        try {
                            var item = items.get(i);
                            if (!item.isObject() || !"steam_community_announcements".equals(text(item, "feedname"))) throw invalid();
                            if (item.has("appid") && (!item.path("appid").canConvertToLong() || item.path("appid").asLong() != seed.appId())) throw invalid();
                            var gid = item.get("gid");
                            if (gid == null || !(gid.isTextual() || gid.isIntegralNumber()) || !gid.asText().matches("[0-9]{1,128}")) throw invalid();
                            var date = item.get("date");
                            if (date == null || !date.isIntegralNumber() || !date.canConvertToLong() || date.asLong() <= 0) throw invalid();
                            String url = text(item, "url");
                            if (!safeOriginal(url)) throw invalid();
                            var clean = content.clean(text(item, "contents"));
                            var result = store.store(new ArticleStore.Draft(source.getId(), game.getId(), gid.asText(),
                                content.title(text(item, "title")), clean.body(), url, Instant.ofEpochSecond(date.asLong()), false, clean.excerpt(), clean.thumbnailUrl()));
                            if (result.created()) saved++; else duplicates++;
                        } catch (RuntimeException error) {
                            failed++;
                            if (errors.size() < 12) errors.add("AppID " + seed.appId() + " 항목 " + (i + 1) + ": " + itemError(error));
                        }
                    }
                } catch (RuntimeException error) {
                    failed++;
                    if (errors.size() < 12) errors.add("AppID " + seed.appId() + ": " + (error instanceof SteamNewsClient.FetchException ? error.getMessage() : "게임 수집 중 오류가 발생했습니다."));
                }
            }
            var status = failed == 0 ? IngestionRun.Status.SUCCESS : successfulGames == 0 || saved + duplicates == 0 ? IngestionRun.Status.FAILED : IngestionRun.Status.PARTIAL;
            String summary = errors.isEmpty() ? null : TextExcerpt.limit(String.join("\n", errors), 1000);
            run.finish(status, fetched, saved, duplicates, failed, summary);
            runs.saveAndFlush(run);
            return RunView.from(runs.findById(run.getId()).orElseThrow());
        } finally { lease.release(source.getId(), owner); }
    }

    @Transactional(readOnly = true)
    public RunPage history(int page, int size) {
        var result = runs.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))));
        return new RunPage(result.stream().map(RunView::from).toList(), page, size, result.getTotalElements(), result.getTotalPages(),
            properties.isEnabled(), properties.isEnabled() && properties.isSchedulingEnabled(), properties.getIntervalMs());
    }
    private static String text(JsonNode item, String key) {
        var value = item.get(key); if (value == null || !value.isTextual()) throw invalid(); return value.asText();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("필수 항목·게임 번호·출처·원문 주소 형식이 올바르지 않습니다."); }
    private static String itemError(RuntimeException error) {
        if (error instanceof org.springframework.dao.DataAccessException) return "DB 저장 실패";
        if (error instanceof ApiException) return "글 저장 입력 검증 실패";
        return "뉴스 항목 형식 또는 정제 결과가 올바르지 않습니다.";
    }
    private static boolean safeOriginal(String value) {
        try {
            var url = URI.create(value); String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(url.getScheme()) && url.getUserInfo() == null && value.length() <= 2048
                && (host.equals("store.steampowered.com") || host.equals("steamcommunity.com") || host.equals("www.steamcommunity.com") || host.equals("steamstore-a.akamaihd.net"));
        } catch (IllegalArgumentException error) { return false; }
    }
}
