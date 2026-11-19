package com.gamenews.articles;

import jakarta.persistence.*;

@Entity
@Table(name = "sources")
public class Source {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50, unique = true)
    private String code;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, length = 20)
    private String type = "OTHER";
    @Column(name = "base_url", length = 2048)
    private String baseUrl;
    @Column(nullable = false)
    private boolean active = true;

    protected Source() {}
    public Source(String code, String name) { this.code = code; this.name = name; this.type = "OTHER"; }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getBaseUrl() { return baseUrl; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public static Source steam() {
        var source = new Source("steam", "Steam 공식 소식");
        source.type = "STEAM";
        source.baseUrl = "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/";
        return source;
    }
}
