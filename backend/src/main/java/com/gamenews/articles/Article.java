package com.gamenews.articles;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "articles")
public class Article {
    public enum Status { PUBLISHED, HIDDEN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "source_id", nullable = false)
    private Source source;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "game_id")
    private Game game;
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;
    @Column(nullable = false, length = 300)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12)
    private Status status;
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    protected Article() {}
    Article(Source source, Game game, ArticleStore.Draft draft) {
        this.source = source; this.game = game; this.externalId = draft.externalId();
        this.title = draft.title().strip(); this.body = draft.body().strip();
        this.originalUrl = draft.originalUrl(); this.publishedAt = draft.publishedAt();
        this.createdAt = Instant.now(); this.status = Status.PUBLISHED; this.demo = draft.demo();
    }
    public Long getId() { return id; }
    public Source getSource() { return source; }
    public Game getGame() { return game; }
    public String getExternalId() { return externalId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public boolean isDemo() { return demo; }
    public void setStatus(Status status) { this.status = status; }
}
