package com.atlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas Confluence Sync Agent. Reads service data from the Atlas DB and writes
 * the 7-section page template to Confluence pages in the configured space.
 *
 * Lives at the {@code com.atlas} package root so default Spring Boot scanning
 * picks up both this module's components ({@code com.atlas.confluence}) and
 * the shared JPA entities and repositories from atlas-domain
 * ({@code com.atlas.services}).
 */
@SpringBootApplication
public class AtlasConfluenceSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtlasConfluenceSyncApplication.class, args);
    }
}
