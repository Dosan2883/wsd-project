package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class SteamNewsClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private URI endpoint;
    private SteamProperties properties;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Long> pauses = new ArrayList<>();
    private static final String VALID = "{\"appnews\":{\"appid\":570,\"newsitems\":[]}}";

    @BeforeEach void start() throws IOException {
        properties = new SteamProperties(); properties.setTimeoutMs(1000); properties.setCount(5);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/news");
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private SteamNewsClient client() { return new SteamNewsClient(new ObjectMapper(), properties, endpoint, pauses::add); }
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    @Test void 일시적오류는재시도하고필요한조회인자를보낸다() {
        server.createContext("/news", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).contains("appid=570", "count=5", "maxlength=0", "feeds=steam_community_announcements");
            respond(exchange, calls.incrementAndGet() == 1 ? 503 : 200, VALID);
        }); server.start();
        assertThat(client().fetch(570)).isEmpty(); assertThat(calls).hasValue(2); assertThat(pauses).containsExactly(250L);
    }
    @Test void 인증오류는반복호출하지않는다() {
        server.createContext("/news", exchange -> { calls.incrementAndGet(); respond(exchange, 403, "거절"); }); server.start();
        assertThatThrownBy(() -> client().fetch(570)).isInstanceOf(SteamNewsClient.FetchException.class).hasMessageContaining("403");
        assertThat(calls).hasValue(1);
    }
    @Test void 요청제한의긴대기안내보다먼저재시도하지않는다() {
        server.createContext("/news", exchange -> { calls.incrementAndGet(); exchange.getResponseHeaders().set("Retry-After", "120"); respond(exchange, 429, "요청 제한"); }); server.start();
        var client = client();
        assertThatThrownBy(() -> client.fetch(570)).hasMessageContaining("429");
        assertThatThrownBy(() -> client.fetch(730)).hasMessageContaining("요청 제한 안내");
        assertThat(calls).hasValue(1); assertThat(pauses).isEmpty();
    }
    @Test void 요청제한의짧은대기안내를반영한다() {
        server.createContext("/news", exchange -> {
            int attempt = calls.incrementAndGet(); exchange.getResponseHeaders().set("Retry-After", "1"); respond(exchange, attempt == 1 ? 429 : 200, VALID);
        }); server.start();
        assertThat(client().fetch(570)).isEmpty(); assertThat(pauses).containsExactly(1000L);
    }
    @Test void 재시도는최대2회이고깨진응답은명확히실패한다() {
        server.createContext("/news", exchange -> { calls.incrementAndGet(); respond(exchange, 500, "장애"); }); server.start();
        assertThatThrownBy(() -> client().fetch(570)).hasMessageContaining("500"); assertThat(calls).hasValue(3);
    }
    @Test void 다른게임응답과잘못된JSON을거절한다() {
        server.createContext("/news", exchange -> respond(exchange, 200, calls.incrementAndGet() == 1 ? VALID.replace("570", "730") : "깨진 JSON")); server.start();
        assertThatThrownBy(() -> client().fetch(570)).hasMessageContaining("게임 번호");
        assertThatThrownBy(() -> client().fetch(570)).hasMessageContaining("JSON");
    }
    @Test void 본문수신이느려도전체요청시간제한이작동한다() {
        properties.setTimeoutMs(100); properties.setMaxRetries(0);
        server.createContext("/news", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write('{'); output.flush(); Thread.sleep(500); output.write('}'); }
            catch (IOException | InterruptedException ignored) { exchange.close(); }
        }); server.start();
        assertThatThrownBy(() -> client().fetch(570)).hasMessageContaining("시간");
    }
    @Test void 큰응답은메모리상한에서중단한다() {
        properties.setMaxRetries(0);
        server.createContext("/news", exchange -> { try { respond(exchange, 200, "a".repeat(4 * 1024 * 1024 + 1)); } catch (IOException ignored) { exchange.close(); } }); server.start();
        assertThatThrownBy(() -> client().fetch(570)).isInstanceOf(SteamNewsClient.FetchException.class);
    }
}
