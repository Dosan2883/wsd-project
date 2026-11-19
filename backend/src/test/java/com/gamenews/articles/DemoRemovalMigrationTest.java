package com.gamenews.articles;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DemoRemovalMigrationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void 데모만삭제하고실제글숨김상태계정과참조게임을보존한다(boolean keepTeamFortressNews) throws Exception {
        String url = "jdbc:h2:mem:remove-demo-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(email,password_hash,role,status,created_at) VALUES ('reader@example.invalid','해시 보존','USER','ACTIVE',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO sources(id,code,name) VALUES (1,'gamelog-demo','데모 출처'),(2,'steam','Steam')");
            sql.executeUpdate("INSERT INTO games(id,name,steam_app_id) VALUES (1,'Team Fortress 2',440),(2,'Dota 2',570)");
            // 전용 출처에서 데모 표시가 누락된 글도 제거한다.
            sql.executeUpdate("INSERT INTO articles(source_id,game_id,external_key,title,body,excerpt,original_url,published_at,created_at,status,is_demo) VALUES "
                + "(1,1,'demo-440','예시','예시 본문','예시','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'PUBLISHED',FALSE),"
                + "(2,2,'marked-demo','숨긴 예시','예시 본문','예시','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'HIDDEN',TRUE),"
                + "(2,2,'real-news','실제 뉴스','보존할 본문','발췌','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'HIDDEN',FALSE)");
            sql.executeUpdate("INSERT INTO ingestion_runs(source_id,trigger_type,started_at,status) VALUES (1,'MANUAL',CURRENT_TIMESTAMP,'SUCCESS'),(2,'MANUAL',CURRENT_TIMESTAMP,'SUCCESS')");
            if (keepTeamFortressNews) {
                sql.executeUpdate("INSERT INTO articles(source_id,game_id,external_key,title,body,excerpt,original_url,published_at,created_at,status,is_demo) VALUES (2,1,'tf2-news','실제 TF2 뉴스','보존할 본문','발췌','https://example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'PUBLISHED',FALSE)");
            }
        }
        var flyway = Flyway.configure().dataSource(url, "sa", "").load();
        flyway.migrate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM articles WHERE is_demo = TRUE OR source_id = 1")) {
                row.next(); assertThat(row.getInt(1)).isZero();
            }
            try (var row = sql.executeQuery("SELECT title,body,status FROM articles WHERE external_key = 'real-news'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("실제 뉴스");
                assertThat(row.getString(2)).isEqualTo("보존할 본문");
                assertThat(row.getString(3)).isEqualTo("HIDDEN");
            }
            for (String table : new String[]{"users", "sources", "ingestion_runs"}) {
                try (var row = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    row.next(); assertThat(row.getInt(1)).isEqualTo(1);
                }
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM games WHERE steam_app_id = 440")) {
                row.next(); assertThat(row.getInt(1)).isEqualTo(keepTeamFortressNews ? 1 : 0);
            }
        }
    }
}
