package com.gamenews.ingestion;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {
    @Override @EntityGraph(attributePaths = "source") Optional<IngestionRun> findById(Long id);
    @Override @EntityGraph(attributePaths = "source") Page<IngestionRun> findAll(Pageable pageable);

    @Modifying @Transactional
    @Query("update IngestionRun r set r.status = :failed, r.finishedAt = :now, r.errorSummary = :message, r.version = r.version + 1 where r.source.id = :sourceId and r.status = :running")
    int closeInterrupted(@Param("sourceId") Long sourceId, @Param("now") Instant now, @Param("message") String message,
                         @Param("failed") IngestionRun.Status failed, @Param("running") IngestionRun.Status running);
}
