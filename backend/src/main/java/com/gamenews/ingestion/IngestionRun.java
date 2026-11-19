package com.gamenews.ingestion;

import com.gamenews.articles.Source;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ingestion_runs")
public class IngestionRun {
    public enum Status { RUNNING, SUCCESS, PARTIAL, FAILED }
    public enum Trigger { MANUAL, SCHEDULED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "source_id", nullable = false) private Source source;
    @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false, length = 20) private Trigger trigger;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "fetched_count", nullable = false) private int fetchedCount;
    @Column(name = "saved_count", nullable = false) private int savedCount;
    @Column(name = "duplicate_count", nullable = false) private int duplicateCount;
    @Column(name = "failed_count", nullable = false) private int failedCount;
    @Column(name = "error_summary", length = 2000) private String errorSummary;
    @Version @Column(nullable = false) private long version;
    protected IngestionRun() {}
    public IngestionRun(Source source, Trigger trigger) {
        this.source = source; this.trigger = trigger; this.startedAt = Instant.now(); this.status = Status.RUNNING;
    }
    public void finish(Status status, int fetched, int saved, int duplicates, int failed, String error) {
        this.status = status; this.fetchedCount = fetched; this.savedCount = saved;
        this.duplicateCount = duplicates; this.failedCount = failed; this.errorSummary = error; this.finishedAt = Instant.now();
    }
    public Long getId() { return id; }
    public Source getSource() { return source; }
    public Trigger getTrigger() { return trigger; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Status getStatus() { return status; }
    public int getFetchedCount() { return fetchedCount; }
    public int getSavedCount() { return savedCount; }
    public int getDuplicateCount() { return duplicateCount; }
    public int getFailedCount() { return failedCount; }
    public String getErrorSummary() { return errorSummary; }
}
