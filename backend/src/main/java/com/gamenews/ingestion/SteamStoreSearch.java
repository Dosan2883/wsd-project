package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamenews.auth.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SteamStoreSearch {
    private static final int PAGE_SIZE = 25;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore requests = new Semaphore(4);
    private final ObjectMapper json;
    private final URI endpoint;

    public record StoreGame(long steamAppId, String name) {}
    public record StorePage(List<StoreGame> items, int page, int totalElements, boolean hasNext) {}

    @Autowired
    public SteamStoreSearch(ObjectMapper json) {
        this(json, URI.create("https://store.steampowered.com/search/results/"));
    }

    SteamStoreSearch(ObjectMapper json, URI endpoint) {
        this.json = json;
        this.endpoint = endpoint;
    }

    public StorePage search(String query, int page) {
        if (!requests.tryAcquire()) throw unavailable();
        try {
            int start = page * PAGE_SIZE;
            String term = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
            var uri = URI.create(endpoint + "?category1=998&infinite=1&cc=kr&l=koreana&start="
                + start + "&count=" + PAGE_SIZE + "&term=" + term);
            var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json").build();
            var future = http.sendAsync(request, info -> new SteamResponseBody(1024 * 1024));
            try {
                var response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) throw unavailable();
                JsonNode result = json.readTree(response.body());
                if (result == null || !result.path("success").asBoolean(false)
                    || !result.path("results_html").isTextual() || !result.path("total_count").canConvertToInt()) {
                    throw unavailable();
                }
                int total = result.path("total_count").asInt();
                if (total < 0) throw unavailable();
                var games = parseGames(result.path("results_html").asText());
                return new StorePage(games, page, total, start + PAGE_SIZE < total);
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (TimeoutException | ExecutionException | IOException error) {
                future.cancel(true);
                throw unavailable();
            }
        } finally {
            requests.release();
        }
    }

    private List<StoreGame> parseGames(String html) {
        var games = new LinkedHashMap<Long, StoreGame>();
        for (var row : Jsoup.parseBodyFragment(html).select("a.search_result_row[data-ds-appid]")) {
            String id = row.attr("data-ds-appid");
            if (!id.matches("[1-9][0-9]{0,9}") || !row.attr("data-ds-itemkey").equals("App_" + id)) continue;
            long appId = Long.parseLong(id);
            var title = row.selectFirst(".title");
            if (appId <= 4294967295L && title != null && !title.text().isBlank()) {
                games.putIfAbsent(appId, new StoreGame(appId, title.text()));
            }
            if (games.size() == PAGE_SIZE) break;
        }
        return List.copyOf(games.values());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STEAM_STORE_UNAVAILABLE", "Steam 게임 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
