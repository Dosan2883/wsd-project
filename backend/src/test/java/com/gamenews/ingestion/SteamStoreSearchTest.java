package com.gamenews.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SteamStoreSearchTest {
    @Test void 상점응답을페이지로조회하고게임이름과앱아이디만반환한다() throws Exception {
        var query = new AtomicReference<String>();
        var mapper = new ObjectMapper();
        String html = """
            <a class="search_result_row" data-ds-appid="105600" data-ds-itemkey="App_105600">
                <span class="title">Terraria &amp; 친구들</span><script>ignored()</script>
            </a>
            <a class="search_result_row" data-ds-appid="105600,570" data-ds-itemkey="Sub_42">
                <span class="title">게임 묶음</span>
            </a>
            """;
        var body = mapper.writeValueAsBytes(Map.of("success", 1, "total_count", 51, "results_html", html));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/results/", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search/results/");
            var page = new SteamStoreSearch(mapper, endpoint).search("Terraria & 친구들", 1);
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalElements()).isEqualTo(51);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.items()).containsExactly(new SteamStoreSearch.StoreGame(105600, "Terraria & 친구들"));
            assertThat(query.get()).contains("category1=998", "start=25", "count=25", "term=Terraria+%26+");
        } finally {
            server.stop(0);
        }
    }
}
