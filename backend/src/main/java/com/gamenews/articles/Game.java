package com.gamenews.articles;

import jakarta.persistence.*;

@Entity
@Table(name = "games")
public class Game {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(name = "steam_app_id", nullable = false, unique = true)
    private Long steamAppId;

    protected Game() {}
    public Game(String name, Long steamAppId) { this.name = name; this.steamAppId = steamAppId; }
    public Long getId() { return id; }
    public String getName() { return name; }
    public Long getSteamAppId() { return steamAppId; }
}
