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

    protected Source() {}
    public Source(String code, String name) { this.code = code; this.name = name; }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
