package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SteamNewsClient implements SteamGateway {
    public static final URI ENDPOINT = URI.create("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/");
    public static class FetchException extends RuntimeException {
        public FetchException(String message) { super(message); }
    }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    private final ObjectMapper json;
    private final SteamProperties properties;
    private final HttpClient http;
    private final URI endpoint;
    private final Pause pause;
    private volatile Instant retryNotBefore = Instant.EPOCH;

    @Autowired
    public SteamNewsClient(ObjectMapper json, SteamProperties properties) {
        this(json, properties, ENDPOINT, Thread::sleep);
    }
    SteamNewsClient(ObjectMapper json, SteamProperties properties, URI endpoint, Pause pause) {
        this.json = json; this.properties = properties; this.endpoint = endpoint; this.pause = pause;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public List<JsonNode> fetch(long appId) {
        return fetch(appId, properties.getTimeoutMs(), properties.getMaxRetries());
    }

    @Override public List<JsonNode> fetchOnDemand(long appId) {
        return fetch(appId, Math.min(properties.getTimeoutMs(), 5000), 0);
    }

    private List<JsonNode> fetch(long appId, int timeoutMs, int maxRetries) {
        if (Instant.now().isBefore(retryNotBefore)) throw new FetchException("Steam 요청 제한 안내에 따라 " + retryNotBefore + " 이후 재시도합니다.");
        URI uri = URI.create(endpoint + "?appid=" + appId + "&count=" + properties.getCount()
            + "&maxlength=0&feeds=steam_community_announcements&format=json");
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json").header("User-Agent", "GameLog-News/0.1").GET().build();
            var future = http.sendAsync(request, info -> new SteamResponseBody(4 * 1024 * 1024));
            try {
                var response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                if (status != 200) {
                    long retryDelay = retryDelay(response, attempt);
                    if (status == 429 && retryDelay > 0) retryNotBefore = Instant.now().plusMillis(retryDelay);
                    boolean retryable = status == 429 || status >= 500 && status <= 599;
                    if (retryable && attempt < maxRetries && retryDelay <= 30000) {
                        pause.sleep(retryDelay); continue;
                    }
                    throw new FetchException("Steam HTTP " + status + (retryDelay > 30000 ? " (서버가 안내한 대기 시간이 길어 다음 실행으로 미룹니다.)" : ""));
                }
                try {
                    JsonNode parsed = json.readTree(response.body());
                    if (parsed == null) throw new FetchException("Steam 응답 JSON이 비어 있습니다.");
                    JsonNode root = parsed.path("appnews");
                    if (!root.path("appid").canConvertToLong() || root.path("appid").asLong() != appId || !root.path("newsitems").isArray()) {
                        throw new FetchException("Steam 응답의 게임 번호 또는 뉴스 목록 형식이 올바르지 않습니다.");
                    }
                    if (root.path("newsitems").size() > 100) throw new FetchException("Steam 응답의 항목 수가 상한을 초과했습니다.");
                    var items = new ArrayList<JsonNode>(); root.path("newsitems").forEach(items::add);
                    return items;
                } catch (java.io.IOException error) {
                    throw new FetchException("Steam 응답 JSON을 읽을 수 없습니다.");
                }
            } catch (InterruptedException error) {
                future.cancel(true); Thread.currentThread().interrupt(); throw new FetchException("Steam 요청이 중단되었습니다.");
            } catch (TimeoutException | ExecutionException error) {
                future.cancel(true);
                if (attempt >= maxRetries) throw new FetchException(error instanceof TimeoutException ? "Steam 요청 시간이 초과되었습니다." : "Steam 연결 또는 응답 수신에 실패했습니다.");
                try { pause.sleep(250L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new FetchException("Steam 재시도가 중단되었습니다."); }
            }
        }
        throw new FetchException("Steam 요청에 실패했습니다.");
    }

    private long retryDelay(HttpResponse<?> response, int attempt) {
        String header = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.max(0, Math.min(Long.parseLong(header), Long.MAX_VALUE / 1000L) * 1000L); }
        catch (NumberFormatException | ArithmeticException ignored) {
            try { return Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
            catch (java.time.DateTimeException invalid) { return 250L << attempt; }
        }
    }

}
