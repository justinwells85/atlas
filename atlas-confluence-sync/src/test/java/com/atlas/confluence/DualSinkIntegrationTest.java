package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Phase 5.8 M3 verification: with both
 * {@link ConfluenceWikiSink} and {@link LocalMarkdownWikiSink} enabled,
 * a single sync run produces Confluence pages AND a complete Markdown
 * vault. Both per-sink ref columns ({@code confluence_page_id} +
 * {@code local_markdown_path}) populate independently on the same
 * service row, and soft-delete cleanup walks both refs.
 */
@SpringBootTest
@Testcontainers
class DualSinkIntegrationTest {

    @TempDir
    static Path vaultRoot;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("atlas.confluence.base-url", wireMock::baseUrl);
        registry.add("atlas.confluence.email", () -> "test@example.com");
        registry.add("atlas.confluence.api-token", () -> "test-token");
        registry.add("atlas.confluence.space-key", () -> "ATLAS");
        registry.add("atlas.confluence.sync.cron", () -> "-");
        registry.add("atlas.wiki.sinks.local-markdown.enabled", () -> "true");
        registry.add("atlas.wiki.sinks.local-markdown.path", () -> vaultRoot.toString());
    }

    @Autowired
    SyncCoordinator coordinator;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM services");
        jdbc.update("DELETE FROM service_changes");
    }

    @Test
    void whenBothSinksEnabled_thenServiceSyncProducesConfluencePagesAndMarkdownVault() throws Exception {
        stubConfluenceForCreate("SVC_PAGE_ID");

        Service s = new Service();
        s.setName("billing-service");
        s.setStatus(ServiceStatus.ACTIVE);
        Service saved = serviceRepository.save(s);

        coordinator.syncOne(saved.getId());

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId())
                .as("Confluence ref persists in confluence_page_id")
                .isEqualTo("SVC_PAGE_ID");
        assertThat(reloaded.getLocalMarkdownPath())
                .as("Markdown ref persists in local_markdown_path on the same row")
                .isEqualTo("services/billing-service/billing-service.md");

        // Markdown vault contains every well-known page + the service page.
        assertThat(vaultRoot.resolve("README.md")).exists();
        assertThat(vaultRoot.resolve("architecture-map.md")).exists();
        assertThat(vaultRoot.resolve("inventory-data-stores.md")).exists();
        assertThat(vaultRoot.resolve("inventory-external-deps.md")).exists();
        assertThat(vaultRoot.resolve("about.md")).exists();
        Path servicePage = vaultRoot.resolve("services/billing-service/billing-service.md");
        assertThat(servicePage).exists();

        String body = Files.readString(servicePage);
        assertThat(body)
                .as("Service page body is Markdown with YAML front matter, not Confluence storage format")
                .startsWith("---\n")
                .contains("title: Service: billing-service")
                .contains("atlas_page_type: service")
                .contains("# Service: billing-service");
    }

    @Test
    void whenServiceSoftDeleted_thenBothSinksCleanUpIndependently() throws Exception {
        stubConfluenceForCreate("SVC_PAGE_ID");

        Service s = new Service();
        s.setName("doomed-service");
        s.setStatus(ServiceStatus.ACTIVE);
        Service saved = serviceRepository.save(s);
        coordinator.syncOne(saved.getId());

        Path servicePage = vaultRoot.resolve("services/doomed-service/doomed-service.md");
        assertThat(servicePage).as("Service page written by first sync").exists();
        assertThat(serviceRepository.findById(saved.getId()).orElseThrow().getConfluencePageId())
                .isEqualTo("SVC_PAGE_ID");

        // Soft-delete via repository.delete() — @SQLDelete rewrites to UPDATE.
        serviceRepository.delete(serviceRepository.findById(saved.getId()).orElseThrow());

        // Stub the Confluence cleanup DELETE.
        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/SVC_PAGE_ID"))
                .willReturn(okJson("{}")));

        coordinator.syncAll();

        // Confluence side: DELETE issued and column nulled.
        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/SVC_PAGE_ID")));
        String confluenceRefAfter = jdbc.queryForObject(
                "SELECT confluence_page_id FROM services WHERE id = ?", String.class, saved.getId());
        String markdownRefAfter = jdbc.queryForObject(
                "SELECT local_markdown_path FROM services WHERE id = ?", String.class, saved.getId());
        assertThat(confluenceRefAfter)
                .as("confluence_page_id nulled after Confluence cleanup")
                .isNull();
        assertThat(markdownRefAfter)
                .as("local_markdown_path nulled after Markdown cleanup")
                .isNull();

        // Markdown side: file gone, parent directory cleaned up.
        assertThat(servicePage).as("Markdown service page deleted on cleanup").doesNotExist();
    }

    private void stubConfluenceForCreate(String servicePageId) {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubWellKnownPage("Atlas — Service Inventory", "LANDING");
        stubWellKnownPage("Inventory: Data Stores", "DS_INV");
        stubWellKnownPage("Inventory: External Dependencies", "ED_INV");
        stubWellKnownPage("About Atlas", "ABOUT");
        stubWellKnownPage("Atlas — Architecture Map", "ARCH_MAP");
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("{\"id\":\"" + servicePageId + "\"}")));
    }

    private void stubWellKnownPage(String title, String pageId) {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages"))
                .withQueryParam("title", equalTo(title))
                .willReturn(okJson("{\"results\":[{\"id\":\"" + pageId + "\"}]}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/" + pageId))
                .willReturn(okJson("{\"id\":\"" + pageId + "\",\"version\":{\"number\":1}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/" + pageId))
                .willReturn(okJson("{\"id\":\"" + pageId + "\",\"version\":{\"number\":2}}")));
    }
}
