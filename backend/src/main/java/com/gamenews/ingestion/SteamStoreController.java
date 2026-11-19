package com.gamenews.ingestion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SteamStoreController {
    private final SteamStoreSearch store;

    public SteamStoreController(SteamStoreSearch store) { this.store = store; }

    @GetMapping("/api/games/steam")
    SteamStoreSearch.StorePage search(@RequestParam(defaultValue = "") @Size(max = 100) String q,
                                     @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page) {
        return store.search(q, page);
    }
}
