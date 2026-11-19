package com.gamenews.articles;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findBySourceIdAndExternalId(Long sourceId, String externalId);

    @EntityGraph(attributePaths = {"source", "game"})
    Optional<Article> findByIdAndStatus(Long id, Article.Status status);

    @EntityGraph(attributePaths = {"source", "game"})
    @Query("select a from Article a where a.status = :status and (:gameId is null or a.game.id = :gameId) and lower(a.title) like :pattern escape '!'")
    Page<Article> findPublicPage(@Param("status") Article.Status status, @Param("gameId") Long gameId,
                                @Param("pattern") String pattern, Pageable pageable);
}
