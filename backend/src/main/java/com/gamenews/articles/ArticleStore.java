package com.gamenews.articles;

import com.gamenews.auth.ApiException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 수집 모듈이 사용하는 서버 내부 저장 경로. */
@Service
public class ArticleStore {
    public record Draft(@NotNull @Positive Long sourceId, @Positive Long gameId,
                        @NotBlank @Size(max = 128) String externalId,
                        @NotBlank @Size(max = 300) String title,
                        @NotBlank @Size(max = 100000) String body,
                        @NotBlank @Size(max = 2048) String originalUrl,
                        @NotNull Instant publishedAt, boolean demo,
                        @NotBlank @Size(max = 600) String excerpt,
                        @Size(max = 2048) String thumbnailUrl) {
        public Draft(Long sourceId, Long gameId, String externalId, String title, String body,
                     String originalUrl, Instant publishedAt, boolean demo) {
            this(sourceId, gameId, externalId, title, body, originalUrl, publishedAt, demo, TextExcerpt.of(body), null);
        }
    }
    public record Stored(Long id, boolean created) {}

    private final ArticleRepository articles;
    private final SourceRepository sources;
    private final GameRepository games;
    private final Validator validator;

    public ArticleStore(ArticleRepository articles, SourceRepository sources, GameRepository games, Validator validator) {
        this.articles = articles; this.sources = sources; this.games = games; this.validator = validator;
    }

    public Stored store(Draft draft) {
        if (draft == null || !validator.validate(draft).isEmpty()) throw invalid();
        try {
            URI url = URI.create(draft.originalUrl());
            if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null || url.getUserInfo() != null) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
        if (draft.thumbnailUrl() != null) {
            try {
                URI thumbnail = URI.create(draft.thumbnailUrl());
                if (!"https".equalsIgnoreCase(thumbnail.getScheme()) || thumbnail.getHost() == null || thumbnail.getUserInfo() != null) throw invalid();
            } catch (IllegalArgumentException error) { throw invalid(); }
        }
        var existing = articles.findBySourceIdAndExternalId(draft.sourceId(), draft.externalId());
        if (existing.isPresent()) return new Stored(existing.get().getId(), false);
        var source = sources.findById(draft.sourceId()).orElseThrow(ArticleStore::invalid);
        var game = draft.gameId() == null ? null : games.findById(draft.gameId()).orElseThrow(ArticleStore::invalid);
        try {
            return new Stored(articles.saveAndFlush(new Article(source, game, draft)).getId(), true);
        } catch (DataIntegrityViolationException error) {
            // 저장 트랜잭션이 끝난 뒤 다시 읽어 동시 수집의 유일 제약 충돌을 처리한다.
            var winner = articles.findBySourceIdAndExternalId(draft.sourceId(), draft.externalId());
            if (winner.isPresent()) return new Stored(winner.get().getId(), false);
            throw error;
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARTICLE", "글의 제목·본문·출처·게임·원문 주소를 확인해 주세요.");
    }
}
