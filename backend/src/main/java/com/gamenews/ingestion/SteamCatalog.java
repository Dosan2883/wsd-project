package com.gamenews.ingestion;

import com.gamenews.articles.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
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
        var source = source();
        GAMES.forEach(seed -> game(seed.appId(), seed.name()));
        return source;
    }

    public Source source() {
        return sources.findByCode("steam").orElseGet(() -> {
            try { return sources.saveAndFlush(Source.steam()); }
            catch (DataIntegrityViolationException race) { return sources.findByCode("steam").orElseThrow(() -> race); }
        });
    }

    public Game game(long appId) {
        return game(appId, null);
    }

    public Game game(long appId, String fallbackName) {
        return games.findBySteamAppId(appId).orElseGet(() -> {
            var name = GAMES.stream().filter(seed -> seed.appId() == appId).map(Seed::name)
                .findFirst().orElseGet(() -> cleanName(fallbackName, appId));
            try { return games.saveAndFlush(new Game(name, appId)); }
            catch (DataIntegrityViolationException race) { return games.findBySteamAppId(appId).orElseThrow(() -> race); }
        });
    }

    public List<Game> allGames() { return games.findAll(Sort.by("id")); }

    private static String cleanName(String hint, long appId) {
        if (hint == null) return "Steam 게임 " + appId;
        String name = hint.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip().replaceAll("\\s+", " ");
        if (name.isBlank()) return "Steam 게임 " + appId;
        if (name.length() <= 150) return name;
        return name.substring(0, Character.isHighSurrogate(name.charAt(149)) ? 149 : 150);
    }
}
