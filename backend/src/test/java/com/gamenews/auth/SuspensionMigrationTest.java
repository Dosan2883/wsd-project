package com.gamenews.auth;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SuspensionMigrationTest {
    @Test void 기존회원과해시를보존하고알수없는과거정지일시를만들지않는다() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(email,password_hash,role,status,created_at) VALUES ('legacy@example.com','original-hash','USER','SUSPENDED',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT password_hash,status,suspension_reason,suspended_at FROM users WHERE email='legacy@example.com'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("password_hash")).isEqualTo("original-hash");
            assertThat(row.getString("status")).isEqualTo("SUSPENDED");
            assertThat(row.getString("suspension_reason")).contains("기존 정지 계정");
            assertThat(row.getObject("suspended_at")).isNull();
        }
    }
}
