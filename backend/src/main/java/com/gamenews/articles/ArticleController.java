package com.gamenews.articles;

import com.gamenews.ingestion.SteamIngestionService;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ArticleController {
    private final ArticleService service;
    private final SteamIngestionService ingestion;

    public ArticleController(ArticleService service, SteamIngestionService ingestion) {
        this.service = service;
        this.ingestion = ingestion;
    }

    @GetMapping("/articles")
    ArticleService.ArticlePage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                   @RequestParam(required = false) @Positive Long gameId,
                                   @RequestParam(required = false) @Min(1) @Max(4294967295L) Long steamAppId,
                                   @RequestParam(required = false) @Size(max = 150) String gameName,
                                   @RequestParam(defaultValue = "") @Size(max = 100) String q) {
        Long targetAppId = gameId == null ? steamAppId : service.steamAppId(gameId);
        if (targetAppId != null && (steamAppId == null || steamAppId.equals(targetAppId))) {
            ingestion.collectForApp(targetAppId, gameName);
        }
        return service.list(page, size, gameId, steamAppId, q);
    }

    @GetMapping("/articles/{id}")
    ArticleService.Detail detail(@PathVariable @Positive Long id) { return service.detail(id); }

    @GetMapping("/games")
    List<ArticleService.GameView> games() { return service.games(); }
}
