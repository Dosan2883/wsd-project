package com.gamenews.articles;

import com.gamenews.auth.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ArticleService {
    public record GameView(Long id, String name, Long steamAppId) {
        static GameView from(Game game) { return game == null ? null : new GameView(game.getId(), game.getName(), game.getSteamAppId()); }
    }
    public record SourceView(String code, String name) {}
    public record Summary(Long id, String title, String excerpt, GameView game, SourceView source, Instant publishedAt, boolean demo, String thumbnailUrl) {}
    public record Detail(Long id, String title, String body, GameView game, SourceView source, String originalUrl,
                         Instant publishedAt, Instant createdAt, boolean demo, String excerpt, String thumbnailUrl) {}
    public record ArticlePage(List<Summary> items, int page, int size, long totalElements, int totalPages) {}

    private final ArticleRepository articles;
    private final GameRepository games;
    public ArticleService(ArticleRepository articles, GameRepository games) { this.articles = articles; this.games = games; }

    public ArticlePage list(int page, int size, Long gameId, Long steamAppId, String query) {
        String escaped = query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        var result = articles.findPublicPage(Article.Status.PUBLISHED, gameId, steamAppId, "%" + escaped + "%",
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))));
        var items = result.map(article -> new Summary(article.getId(), article.getTitle(), article.getExcerpt(),
            GameView.from(article.getGame()), source(article), article.getPublishedAt(), article.isDemo(), article.getThumbnailUrl())).getContent();
        return new ArticlePage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    public Detail detail(Long id) {
        var article = articles.findByIdAndStatus(id, Article.Status.PUBLISHED)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "글을 찾을 수 없거나 공개되지 않은 글입니다."));
        return new Detail(article.getId(), article.getTitle(), article.getBody(), GameView.from(article.getGame()),
            source(article), article.getOriginalUrl(), article.getPublishedAt(), article.getCreatedAt(), article.isDemo(), article.getExcerpt(), article.getThumbnailUrl());
    }

    public List<GameView> games() { return games.findAll(Sort.by("name")).stream().map(GameView::from).toList(); }
    public Long steamAppId(Long gameId) { return games.findById(gameId).map(Game::getSteamAppId).orElse(null); }
    private static SourceView source(Article article) { return new SourceView(article.getSource().getCode(), article.getSource().getName()); }
}
