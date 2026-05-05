package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies multi-sink fan-out: with the live {@link ConfluenceWikiSink}
 * AND a second {@link RecordingWikiSink} bean both registered, the
 * coordinator dispatches each logical create/update/find call to both
 * sinks. The recording sink stands in for any future non-Confluence sink
 * (Markdown will arrive in M3). Confluence-side behavior is asserted via
 * WireMock; recording-side behavior is asserted via the captured calls.
 */
@SpringBootTest
@Testcontainers
class WikiSinkFanOutTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void confluenceProps(DynamicPropertyRegistry registry) {
        registry.add("atlas.confluence.base-url", wireMock::baseUrl);
        registry.add("atlas.confluence.email", () -> "test@example.com");
        registry.add("atlas.confluence.api-token", () -> "test-token");
        registry.add("atlas.confluence.space-key", () -> "ATLAS");
        registry.add("atlas.confluence.sync.cron", () -> "-");
    }

    @TestConfiguration
    static class FakeSinkConfig {
        @Bean
        RecordingWikiSink fakeSink() {
            return new RecordingWikiSink("recording");
        }
    }

    @Autowired
    SyncCoordinator coordinator;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    RecordingWikiSink recordingSink;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM services");
        jdbc.update("DELETE FROM service_changes");
    }

    @Test
    void whenServiceSyncedWithMultipleSinksEnabled_thenServicePageCreatedOnEverySink() {
        stubConfluenceForCreate();

        Service s = new Service();
        s.setName("billing-service");
        s.setStatus(ServiceStatus.ACTIVE);
        Service saved = serviceRepository.save(s);

        coordinator.syncOne(saved.getId());

        // Confluence sink: existing parity behavior — service page is POSTed.
        // (Validated by the existing SyncCoordinatorIntegrationTest in detail.)
        // Recording sink: same logical create call surfaces here.
        assertThat(recordingSink.createdTitles())
                .as("Recording sink should receive the service page create call")
                .contains("Service: billing-service");

        // Confluence sink continues to drive the persisted page id; the
        // recording sink's returned ref is observed but not persisted in M1
        // (V25 + per-sink columns land in M3).
        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId())
                .as("Confluence ref persists; recording sink's ref is dropped in M1")
                .isEqualTo("CONFLUENCE_NEW_ID");
    }

    @Test
    void whenSyncRunsWithMultipleSinks_thenWellKnownPagesCreatedOnEverySink() {
        stubConfluenceForCreate();

        Service s = new Service();
        s.setName("billing-service");
        s.setStatus(ServiceStatus.ACTIVE);
        serviceRepository.save(s);

        coordinator.syncAll();

        // Each well-known page (landing + 2 inventory + about + arch map)
        // is looked up by title on each sink. The recording sink returns
        // empty, forcing a create call on the recording side. (On the
        // Confluence side, WireMock returns existing IDs — so the create
        // path doesn't fire there. That asymmetry is fine: each sink's
        // lifecycle decisions are local to the sink.)
        assertThat(recordingSink.findByTitleCalls())
                .contains(
                        "Atlas — Service Inventory",
                        "Inventory: Data Stores",
                        "Inventory: External Dependencies",
                        "About Atlas",
                        "Atlas — Architecture Map");
        assertThat(recordingSink.createdTitles())
                .contains(
                        "Atlas — Service Inventory",
                        "Inventory: Data Stores",
                        "Inventory: External Dependencies",
                        "About Atlas",
                        "Atlas — Architecture Map",
                        "Service: billing-service");
    }

    private void stubConfluenceForCreate() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        // Well-known pages: pretend each already exists in Confluence so the
        // create vs. update path is deterministic for the recording-side
        // assertions. On the Confluence side these become PUTs; on the
        // recording side they become CREATEs (because the recording sink's
        // findByTitle returns empty).
        stubWellKnownPage("Atlas — Service Inventory", "LANDING");
        stubWellKnownPage("Inventory: Data Stores", "DS_INV");
        stubWellKnownPage("Inventory: External Dependencies", "ED_INV");
        stubWellKnownPage("About Atlas", "ABOUT");
        stubWellKnownPage("Atlas — Architecture Map", "ARCH_MAP");
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("""
                        {"id":"CONFLUENCE_NEW_ID"}
                        """)));
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
