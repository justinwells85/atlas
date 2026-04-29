package com.atlas.codesync;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * HTTPS GET for an OpenAPI spec by URL. Returns the raw spec text; parsing is
 * {@link OpenApiParser}'s job. Errors (404, 5xx, timeout) bubble up as
 * RestClient exceptions; the caller treats them as transient and skips the
 * refresh without mutating the DB.
 */
@Component
public class OpenApiFetcher {

    private final RestClient http;

    public OpenApiFetcher() {
        this.http = RestClient.builder()
                .build();
    }

    public String fetch(String url) {
        return http.get()
                .uri(url)
                .retrieve()
                .body(String.class);
    }
}
