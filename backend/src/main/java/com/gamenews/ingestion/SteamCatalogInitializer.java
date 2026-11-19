package com.gamenews.ingestion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ingestion.steam", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SteamCatalogInitializer implements ApplicationRunner {
    private final SteamCatalog catalog;
    public SteamCatalogInitializer(SteamCatalog catalog) { this.catalog = catalog; }
    @Override public void run(ApplicationArguments args) { catalog.prepare(); }
}
