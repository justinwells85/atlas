package com.atlas.confluence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper over the Confluence Cloud v2 REST API. The architectural seam
 * for the sync agent: WireMock stands in here during tests so {@code mvn verify}
 * does not require network access to the real Confluence instance.
 *
 * Confluence v2 reference: https://developer.atlassian.com/cloud/confluence/rest/v2/
 */
@Component
@ConditionalOnProperty(
        prefix = "atlas.wiki.sinks.confluence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ConfluenceClient {

    private final RestClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfluenceClient(
            @Value("${atlas.confluence.base-url}") String baseUrl,
            @Value("${atlas.confluence.email}") String email,
            @Value("${atlas.confluence.api-token}") String apiToken) {
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", basicAuth)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** Resolve the numeric space ID for a space key (e.g., "ATLAS" → "589827"). */
    public String getSpaceIdByKey(String key) {
        Map<String, Object> resp = http.get()
                .uri(uri -> uri.path("/api/v2/spaces").queryParam("keys", key).build())
                .retrieve()
                .body(MAP_TYPE);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("No Confluence space found with key " + key);
        }
        return (String) results.get(0).get("id");
    }

    /**
     * Look up a page by exact title within a space. Used to resolve well-known
     * pages (landing page, inventory pages) without storing their IDs in our
     * DB — Confluence is the source of truth for page IDs.
     */
    public Optional<String> findPageByTitle(String spaceId, String title) {
        Map<String, Object> resp = http.get()
                .uri(uri -> uri.path("/api/v2/pages")
                        .queryParam("space-id", spaceId)
                        .queryParam("title", title)
                        .build())
                .retrieve()
                .body(MAP_TYPE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable((String) results.get(0).get("id"));
    }

    /**
     * Create a new page. Pass {@code parentId = null} for a top-level page in
     * the space; pass a parent page ID to nest the new page underneath it.
     */
    public String createPage(String spaceId, String title, String storageBody, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("spaceId", spaceId);
        body.put("status", "current");
        body.put("title", title);
        body.put("body", Map.of("representation", "storage", "value", storageBody));
        if (parentId != null && !parentId.isBlank()) {
            body.put("parentId", parentId);
        }

        Map<String, Object> resp = http.post()
                .uri("/api/v2/pages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .body(MAP_TYPE);

        return (String) resp.get("id");
    }

    /**
     * Update an existing page. Confluence v2 PUT requires the next version
     * number, so this method first GETs the current page to read its version,
     * then PUTs with version.number + 1. Pass {@code parentId} to re-parent
     * the page (useful when migrating pages under a new landing page); pass
     * {@code null} to leave the parent as-is.
     */
    public void updatePage(String pageId, String title, String storageBody, String parentId) {
        Map<String, Object> existing;
        try {
            existing = http.get()
                    .uri("/api/v2/pages/{id}", pageId)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ConfluencePageNotFoundException(pageId, e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> version = (Map<String, Object>) existing.get("version");
        int nextVersion = ((Number) version.get("number")).intValue() + 1;

        Map<String, Object> body = new HashMap<>();
        body.put("id", pageId);
        body.put("status", "current");
        body.put("title", title);
        body.put("body", Map.of("representation", "storage", "value", storageBody));
        body.put("version", Map.of("number", nextVersion, "message", "Atlas sync"));
        if (parentId != null && !parentId.isBlank()) {
            body.put("parentId", parentId);
        }

        http.put()
                .uri("/api/v2/pages/{id}", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Delete a page. A 404 (page already gone) is treated as success — the
     * end state is what matters, and that's the only sensible response when
     * the cleanup pass runs against a page someone already deleted manually.
     */
    public void deletePage(String pageId) {
        try {
            http.delete()
                    .uri("/api/v2/pages/{id}", pageId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Already deleted in Confluence; treat as success.
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Confluence request body", e);
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
}
