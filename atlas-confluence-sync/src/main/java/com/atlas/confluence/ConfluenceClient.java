package com.atlas.confluence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the Confluence Cloud v2 REST API. The architectural seam
 * for the sync agent: WireMock stands in here during tests so {@code mvn verify}
 * does not require network access to the real Confluence instance.
 *
 * Confluence v2 reference: https://developer.atlassian.com/cloud/confluence/rest/v2/
 */
@Component
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

    /** Create a new page at space root. Returns the new page's ID. */
    public String createPage(String spaceId, String title, String storageBody) {
        Map<String, Object> body = Map.of(
                "spaceId", spaceId,
                "status", "current",
                "title", title,
                "body", Map.of(
                        "representation", "storage",
                        "value", storageBody));

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
     * then PUTs with version.number + 1.
     */
    public void updatePage(String pageId, String title, String storageBody) {
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

        Map<String, Object> body = Map.of(
                "id", pageId,
                "status", "current",
                "title", title,
                "body", Map.of(
                        "representation", "storage",
                        "value", storageBody),
                "version", Map.of(
                        "number", nextVersion,
                        "message", "Atlas sync"));

        http.put()
                .uri("/api/v2/pages/{id}", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .toBodilessEntity();
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
