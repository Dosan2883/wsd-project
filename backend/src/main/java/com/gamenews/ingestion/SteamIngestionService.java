package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamenews.articles.*;
import com.gamenews.auth.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
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
    private final Semaphore onDemandSlots = new Semaphore(4);
    // 두 맵은 runningApps 잠금 아래에서만 읽고 변경한다.
    private final Map<Long, CompletableFuture<GameResult>> runningApps = new HashMap<>();
    private final LinkedHashMap<Long, Instant> nextRefresh = new LinkedHashMap<>(128, 0.75f, true);

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
            var totals = new GameResult();
            Runnable renew = () -> lease.renew(source.getId(), owner);
            for (var game : catalog.allGames()) {
                totals.add(collectGame(source, game.getSteamAppId(), game.getName(), false, renew));
            }
            var status = totals.failed == 0 ? IngestionRun.Status.SUCCESS
                : totals.saved + totals.duplicates == 0 ? IngestionRun.Status.FAILED : IngestionRun.Status.PARTIAL;
            String summary = totals.errors.isEmpty() ? null : TextExcerpt.limit(String.join("\n", totals.errors), 1000);
            run.finish(status, totals.fetched, totals.saved, totals.duplicates, totals.failed, summary);
            runs.saveAndFlush(run);
            return RunView.from(runs.findById(run.getId()).orElseThrow());
        } finally { lease.release(source.getId(), owner); }
    }

    public void collectForApp(long appId, String gameName) {
        if (!properties.isEnabled()) return;
        try {
            Source source = catalog.source();
            if (source.isActive()) collectGame(source, appId, gameName, true, () -> {});
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(getClass()).warn("AppID {} 즉시 수집 준비 실패: {}", appId, error.getClass().getSimpleName());
        }
    }

    private GameResult collectGame(Source source, long appId, String gameName, boolean onDemand, Runnable renew) {
        var own = new CompletableFuture<GameResult>();
        CompletableFuture<GameResult> active;
        synchronized (runningApps) {
            active = runningApps.get(appId);
            if (active == null) {
                if (onDemand && (Instant.now().isBefore(nextRefresh.getOrDefault(appId, Instant.EPOCH))
                    || !onDemandSlots.tryAcquire())) return null;
                runningApps.put(appId, own);
            }
        }
        if (active != null) return awaitGame(appId, active, onDemand);

        try {
            GameResult result = fetchAndSave(source, appId, gameName, onDemand, renew);
            synchronized (runningApps) {
                nextRefresh.put(appId, Instant.now().plusSeconds(result.failed == 0 ? 600 : 30));
                if (nextRefresh.size() > 4096) nextRefresh.pollFirstEntry();
            }
            own.complete(result);
            if (onDemand && result.failed > 0) {
                LoggerFactory.getLogger(getClass()).warn("AppID {} 즉시 수집 실패 항목 {}건: {}", appId, result.failed, result.errors.getFirst());
            }
            return result;
        } catch (RuntimeException | Error error) {
            own.completeExceptionally(error);
            throw error;
        } finally {
            synchronized (runningApps) { runningApps.remove(appId); }
            if (onDemand) onDemandSlots.release();
        }
    }

    private static GameResult awaitGame(long appId, CompletableFuture<GameResult> active, boolean onDemand) {
        try {
            return onDemand ? active.get(6, TimeUnit.SECONDS) : active.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // 기다릴 수 없으면 DB에 저장된 뉴스만 조회한다.
        }
        var result = new GameResult();
        result.fail("AppID " + appId + ": 진행 중인 수집 결과를 기다리지 못했습니다.");
        return result;
    }

    private GameResult fetchAndSave(Source source, long appId, String gameName, boolean onDemand, Runnable renew) {
        var result = new GameResult();
        try {
            renew.run();
            var items = onDemand ? client.fetchOnDemand(appId) : client.fetch(appId);
            result.fetched = items.size();
            renew.run();
            // Steam에서 해당 게임 응답을 받은 뒤에만 공개 게임 목록에 추가한다.
            var game = catalog.game(appId, gameName);
            for (int i = 0; i < items.size(); i++) {
                renew.run();
                try {
                    var stored = storeItem(source, game, items.get(i));
                    if (stored.created()) result.saved++; else result.duplicates++;
                } catch (RuntimeException error) {
                    result.fail("AppID " + appId + " 항목 " + (i + 1) + ": " + itemError(error));
                }
            }
        } catch (RuntimeException error) {
            result.fail("AppID " + appId + ": " + (error instanceof SteamNewsClient.FetchException
                ? error.getMessage() : "게임 수집 중 오류가 발생했습니다."));
        }
        return result;
    }

    private ArticleStore.Stored storeItem(Source source, Game game, JsonNode item) {
        if (!item.isObject() || !"steam_community_announcements".equals(text(item, "feedname"))) throw invalid();
        if (item.has("appid") && (!item.path("appid").canConvertToLong() || item.path("appid").asLong() != game.getSteamAppId())) throw invalid();
        var gid = item.get("gid");
        if (gid == null || !(gid.isTextual() || gid.isIntegralNumber()) || !gid.asText().matches("[0-9]{1,128}")) throw invalid();
        var date = item.get("date");
        if (date == null || !date.isIntegralNumber() || !date.canConvertToLong() || date.asLong() <= 0) throw invalid();
        String url = text(item, "url");
        if (!safeOriginal(url)) throw invalid();
        var clean = content.clean(text(item, "contents"));
        return store.store(new ArticleStore.Draft(source.getId(), game.getId(), gid.asText(),
            content.title(text(item, "title")), clean.body(), url, Instant.ofEpochSecond(date.asLong()), false, clean.excerpt(), clean.thumbnailUrl()));
    }

    private static final class GameResult {
        int fetched, saved, duplicates, failed;
        final List<String> errors = new ArrayList<>();

        void fail(String message) {
            failed++;
            if (errors.size() < 12) errors.add(message);
        }

        void add(GameResult other) {
            fetched += other.fetched;
            saved += other.saved;
            duplicates += other.duplicates;
            failed += other.failed;
            errors.addAll(other.errors.subList(0, Math.min(12 - errors.size(), other.errors.size())));
        }
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
