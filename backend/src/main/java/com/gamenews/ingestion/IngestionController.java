package com.gamenews.ingestion;

import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ingestions")
public class IngestionController {
    private final SteamIngestionService service;
    public IngestionController(SteamIngestionService service) { this.service = service; }
    @PostMapping SteamIngestionService.RunView collect() { return service.collect(IngestionRun.Trigger.MANUAL); }
    @GetMapping SteamIngestionService.RunPage history(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                                     @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return service.history(page, size);
    }
}
