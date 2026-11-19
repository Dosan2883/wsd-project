package com.gamenews.ingestion;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IngestionLease {
    private final JdbcTemplate jdbc;
    public IngestionLease(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean acquire(Long sourceId, String owner) {
        Instant now = Instant.now();
        return jdbc.update("UPDATE sources SET ingestion_owner=?, ingestion_lease_until=? WHERE id=? AND active=TRUE AND (ingestion_owner IS NULL OR ingestion_lease_until < ?)",
            owner, Timestamp.from(now.plusSeconds(600)), sourceId, Timestamp.from(now)) == 1;
    }
    public void renew(Long sourceId, String owner) {
        Instant now = Instant.now();
        if (jdbc.update("UPDATE sources SET ingestion_lease_until=? WHERE id=? AND ingestion_owner=? AND ingestion_lease_until>=? AND active=TRUE",
            Timestamp.from(now.plusSeconds(600)), sourceId, owner, Timestamp.from(now)) != 1) {
            throw new IllegalStateException("수집 실행권이 만료되었거나 출처가 비활성화되었습니다.");
        }
    }
    public void release(Long sourceId, String owner) {
        jdbc.update("UPDATE sources SET ingestion_owner=NULL, ingestion_lease_until=NULL WHERE id=? AND ingestion_owner=?", sourceId, owner);
    }
}
