package com.gamenews.articles;

import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ArticleController {
    private final ArticleService service;
    public ArticleController(ArticleService service) { this.service = service; }

    @GetMapping("/articles")
    ArticleService.ArticlePage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                   @RequestParam(required = false) @Positive Long gameId,
                                   @RequestParam(defaultValue = "") @Size(max = 100) String q) {
        return service.list(page, size, gameId, q);
    }

    @GetMapping("/articles/{id}")
    ArticleService.Detail detail(@PathVariable @Positive Long id) { return service.detail(id); }

    @GetMapping("/games")
    List<ArticleService.GameView> games() { return service.games(); }
}
