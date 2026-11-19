package com.gamenews.ingestion;

import com.gamenews.articles.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class SteamCatalog {
    public record Seed(long appId, String name) {}
    public static final List<Seed> GAMES = List.of(new Seed(730, "Counter-Strike 2"), new Seed(570, "Dota 2"),
        new Seed(578080, "PUBG: BATTLEGROUNDS"), new Seed(1245620, "ELDEN RING"), new Seed(1086940, "Baldur's Gate 3"));
    private final SourceRepository sources;
    private final GameRepository games;
    public SteamCatalog(SourceRepository sources, GameRepository games) { this.sources = sources; this.games = games; }
    public Source prepare() {
        var source = sources.findByCode("steam").orElseGet(() -> {
            try { return sources.saveAndFlush(Source.steam()); }
            catch (DataIntegrityViolationException race) { return sources.findByCode("steam").orElseThrow(() -> race); }
        });
        GAMES.forEach(seed -> game(seed.appId()));
        return source;
    }
    public Game game(long appId) {
        var seed = GAMES.stream().filter(item -> item.appId() == appId).findFirst().orElseThrow();
        return games.findBySteamAppId(appId).orElseGet(() -> {
            try { return games.saveAndFlush(new Game(seed.name(), appId)); }
            catch (DataIntegrityViolationException race) { return games.findBySteamAppId(appId).orElseThrow(() -> race); }
        });
    }
}
