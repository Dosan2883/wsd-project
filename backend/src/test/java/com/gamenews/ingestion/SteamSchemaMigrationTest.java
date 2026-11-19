package com.gamenews.ingestion;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SteamSchemaMigrationTest {
    @Test void 기존글의외부키숨김상태와본문을보존하며수집스키마로이관한다() throws Exception {
        String url = "jdbc:h2:mem:steam-migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sources(id,code,name) VALUES (1,'existing','기존 출처')");
            statement.executeUpdate("INSERT INTO games(id,name,steam_app_id) VALUES (1,'기존 게임',570)");
            statement.executeUpdate("INSERT INTO articles(source_id,game_id,external_id,title,body,original_url,published_at,created_at,status,is_demo) VALUES (1,1,'18446744073709551615','기존 글','기존 본문','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'HIDDEN',FALSE)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT external_key,body,excerpt,status,version,game_id FROM articles")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("external_key")).isEqualTo("18446744073709551615");
            assertThat(row.getString("body")).isEqualTo("기존 본문");
            assertThat(row.getString("excerpt")).isEqualTo("기존 본문");
            assertThat(row.getString("status")).isEqualTo("HIDDEN");
            assertThat(row.getLong("version")).isZero(); assertThat(row.getLong("game_id")).isEqualTo(1);
        }
    }
}
