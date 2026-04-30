package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SyncCoordinatorIntegrationTest {

    private static final String LANDING_ID = "LANDING";
    private static final String DS_INV_ID = "DS_INV";
    private static final String ED_INV_ID = "ED_INV";
    private static final String ABOUT_ID = "ABOUT";
    private static final String ARCH_MAP_ID = "ARCH_MAP";

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

    @Autowired
    SyncCoordinator coordinator;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    ServiceRelationshipsRepository relationships;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        // Native hard-DELETE — repository.deleteAll() now soft-deletes
        // (V11 / ADR-014); we want a true clean slate between methods.
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM services");
        jdbc.update("DELETE FROM service_changes");
    }

    /**
     * Stub the well-known page interactions (landing + two inventory pages)
     * so each test can focus on its service-page assertions. Pretends each
     * page already exists; coordinator finds them on lookup, then GETs
     * version + PUTs the refreshed body during the sync.
     */
    private void stubLandingPageExists() {
        stubWellKnownPage("Atlas — Service Inventory", LANDING_ID);
        stubWellKnownPage("Inventory: Data Stores", DS_INV_ID);
        stubWellKnownPage("Inventory: External Dependencies", ED_INV_ID);
        stubWellKnownPage("About Atlas", ABOUT_ID);
        stubWellKnownPage("Atlas — Architecture Map", ARCH_MAP_ID);
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

    @Test
    void whenServiceHasNoPageId_thenCreatesPageAndPersistsId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: billing-service")))
                .willReturn(okJson("""
                        {"id":"NEW123","title":"Service: billing-service"}
                        """)));

        Service s = new Service();
        s.setName("billing-service");
        s.setDescription("Handles billing");
        s.setStatus(ServiceStatus.ACTIVE);
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("NEW123");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: billing-service")))
                .withRequestBody(matchingJsonPath("$.spaceId", equalTo("589827")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo(LANDING_ID)))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage"))));
    }

    @Test
    void whenServiceAlreadyHasPageId_thenUpdatesPageAndKeepsId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .willReturn(okJson("""
                        {"id":"EXISTING123","title":"Service: checkout-service","version":{"number":3}}
                        """)));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .willReturn(okJson("""
                        {"id":"EXISTING123","title":"Service: checkout-service","version":{"number":4}}
                        """)));

        Service s = new Service();
        s.setName("checkout-service");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("EXISTING123");
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("EXISTING123");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: checkout-service")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo(LANDING_ID)))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage")))
                .withRequestBody(matchingJsonPath("$.body.value", containing("<h2>Overview</h2>")))
                .withRequestBody(matchingJsonPath("$.version.number", equalTo("4"))));
    }

    @Test
    void whenUpdateReturns404_thenCoordinatorRecreatesPageAndPersistsNewId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/STALE_ID"))
                .willReturn(aResponse().withStatus(404).withBody("page deleted")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: orphan-service")))
                .willReturn(okJson("""
                        {"id":"FRESH_ID"}
                        """)));

        Service s = new Service();
        s.setName("orphan-service");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("STALE_ID");
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("FRESH_ID");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: orphan-service"))));
    }

    @Test
    void whenSyncAllOnEmptyDb_thenReturnsZeroCountsAndMakesNoConfluenceCalls() {
        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v2/spaces")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v2/pages")));
    }

    @Test
    void whenSyncAllAndOneServiceFails_thenOthersStillSyncAndFailureIsReported() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: svc-good-1")))
                .willReturn(okJson("""
                        {"id":"PAGE_GOOD_1"}
                        """)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: svc-bad")))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: svc-good-2")))
                .willReturn(okJson("""
                        {"id":"PAGE_GOOD_2"}
                        """)));

        Service good1 = createService("svc-good-1");
        Service bad = createService("svc-bad");
        Service good2 = createService("svc-good-2");

        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures()).extracting(SyncFailure::serviceName)
                .containsExactly("svc-bad");

        assertThat(serviceRepository.findById(good1.getId()).orElseThrow().getConfluencePageId())
                .isEqualTo("PAGE_GOOD_1");
        assertThat(serviceRepository.findById(good2.getId()).orElseThrow().getConfluencePageId())
                .isEqualTo("PAGE_GOOD_2");
        assertThat(serviceRepository.findById(bad.getId()).orElseThrow().getConfluencePageId())
                .isNull();
    }

    @Test
    void whenServiceIsSoftDeleted_thenSyncCleansUpItsConfluencePage() {
        // Create + sync a service so it has a confluence_page_id.
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: doomed-service")))
                .willReturn(okJson("{\"id\":\"DOOMED_PAGE\"}")));

        Service s = createService("doomed-service");
        coordinator.syncOne(s.getId());
        java.util.UUID svcId = s.getId();
        assertThat(serviceRepository.findById(svcId).orElseThrow().getConfluencePageId())
                .isEqualTo("DOOMED_PAGE");

        // Soft-delete via repository.delete() — @SQLDelete rewrites to UPDATE.
        serviceRepository.delete(serviceRepository.findById(svcId).orElseThrow());

        // Stub the cleanup DELETE; reset old stubs on the page path so the
        // cleanup pass cleanly hits the delete.
        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/DOOMED_PAGE"))
                .willReturn(aResponse().withStatus(204)));

        SyncResult result = coordinator.syncAll();

        // No live services left → success/failure counts both zero.
        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();

        // Cleanup happened.
        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/DOOMED_PAGE")));

        // Row still exists (soft-deleted) with confluence_page_id nulled out.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE id = ? AND deleted_at IS NOT NULL " +
                        "AND confluence_page_id IS NULL",
                Long.class, svcId);
        assertThat(count).isEqualTo(1L);

        // Re-running sync is a no-op on this row (idempotent).
        wireMock.resetRequests();
        coordinator.syncAll();
        wireMock.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v2/pages/DOOMED_PAGE")));
    }

    @Test
    void whenCleanupHits404OnAlreadyDeletedPage_thenStillNullsOutPageId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: ghost-service")))
                .willReturn(okJson("{\"id\":\"GHOST_PAGE\"}")));

        Service s = createService("ghost-service");
        coordinator.syncOne(s.getId());
        java.util.UUID svcId = s.getId();

        serviceRepository.delete(serviceRepository.findById(svcId).orElseThrow());

        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/GHOST_PAGE"))
                .willReturn(aResponse().withStatus(404)));

        coordinator.syncAll();

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE id = ? AND confluence_page_id IS NULL",
                Long.class, svcId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenSyncOneCalledOnUnknownServiceId_thenThrows() {
        java.util.UUID nonexistent = java.util.UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.syncOne(nonexistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(nonexistent.toString());
    }

    @Test
    void whenSyncAllRuns_thenArchitectureMapPageIsRefreshedWithLiveDependencyEdges() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("{\"id\":\"NEW_SVC_PAGE\"}")));

        Service domain = createService("atlas-domain");
        Service mcp = createService("atlas-mcp");
        // Domain is upstream of MCP: MCP depends on domain.
        jdbc.update("INSERT INTO service_dependencies (id, upstream_service_id, downstream_service_id, description) " +
                        "VALUES (?, ?, ?, ?)",
                java.util.UUID.randomUUID(), domain.getId(), mcp.getId(), "JPA repos");

        coordinator.syncAll();

        // The architecture-map well-known page is PUT with a body containing a
        // mermaid block and the live edge. JSON serialization escapes "-->" as
        // "-->" inside the request body string.
        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/" + ARCH_MAP_ID))
                .withRequestBody(containing("flowchart"))
                .withRequestBody(containing("atlas-domain"))
                .withRequestBody(containing("atlas-mcp")));
    }

    @Test
    void whenServiceHasApiRows_thenEachEndpointGetsItsOwnPageParentedUnderTheServicePage() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: orders-service")))
                .willReturn(okJson("{\"id\":\"SVC_PAGE\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("orders-service — POST /v1/orders")))
                .willReturn(okJson("{\"id\":\"EP_POST_ORDERS\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("orders-service — GET /v1/health")))
                .willReturn(okJson("{\"id\":\"EP_GET_HEALTH\"}")));

        Service s = createService("orders-service");
        java.util.UUID postId = relationships.insertApi(s.getId(), "/v1/orders", "POST",
                "bearer", "Create an order", "intake");
        java.util.UUID getId = relationships.insertApi(s.getId(), "/v1/health", "GET",
                null, "Health probe", "intake");

        coordinator.syncOne(s.getId());

        // Each endpoint page is POSTed with the service page as its parent.
        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("orders-service — POST /v1/orders")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo("SVC_PAGE"))));
        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("orders-service — GET /v1/health")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo("SVC_PAGE"))));

        // Both api rows now carry the page IDs returned by Confluence.
        java.util.Map<String, String> pageIds = new java.util.HashMap<>();
        for (ApiSummary a : relationships.findApisFor(s.getId())) {
            pageIds.put(a.method() + " " + a.path(), a.confluencePageId());
        }
        assertThat(pageIds).containsEntry("POST /v1/orders", "EP_POST_ORDERS");
        assertThat(pageIds).containsEntry("GET /v1/health", "EP_GET_HEALTH");

        // Sanity: postId / getId still resolve via findApisFor.
        assertThat(pageIds).hasSize(2);
        assertThat(postId).isNotNull();
        assertThat(getId).isNotNull();
    }

    @Test
    void whenApiAlreadyHasEndpointPageId_thenSyncUpdatesItRatherThanCreatingNewPage() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/SVC_PRE"))
                .willReturn(okJson("{\"id\":\"SVC_PRE\",\"version\":{\"number\":1}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/SVC_PRE"))
                .willReturn(okJson("{\"id\":\"SVC_PRE\",\"version\":{\"number\":2}}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/EP_PRE"))
                .willReturn(okJson("{\"id\":\"EP_PRE\",\"version\":{\"number\":3}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/EP_PRE"))
                .willReturn(okJson("{\"id\":\"EP_PRE\",\"version\":{\"number\":4}}")));

        Service s = new Service();
        s.setName("settled-service");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("SVC_PRE");
        s = serviceRepository.save(s);
        java.util.UUID apiId = relationships.insertApi(s.getId(), "/v1/x", "GET",
                null, "x endpoint", "openapi");
        relationships.setApiConfluencePageId(apiId, "EP_PRE");

        coordinator.syncOne(s.getId());

        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/EP_PRE"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("settled-service — GET /v1/x"))));
        // No POSTs to /api/v2/pages for the endpoint should have fired.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("settled-service — GET /v1/x"))));
    }

    @Test
    void whenEndpointPagePersistedIdNoLongerExistsInConfluence_thenSyncRecreatesAndUpdatesId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/SVC_OK"))
                .willReturn(okJson("{\"id\":\"SVC_OK\",\"version\":{\"number\":1}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/SVC_OK"))
                .willReturn(okJson("{\"id\":\"SVC_OK\",\"version\":{\"number\":2}}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/EP_GONE"))
                .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("ghost-svc — GET /v1/x")))
                .willReturn(okJson("{\"id\":\"EP_FRESH\"}")));

        Service s = new Service();
        s.setName("ghost-svc");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("SVC_OK");
        s = serviceRepository.save(s);
        java.util.UUID apiId = relationships.insertApi(s.getId(), "/v1/x", "GET",
                null, "x", "openapi");
        relationships.setApiConfluencePageId(apiId, "EP_GONE");

        coordinator.syncOne(s.getId());

        ApiSummary refreshed = relationships.findApisFor(s.getId()).get(0);
        assertThat(refreshed.confluencePageId()).isEqualTo("EP_FRESH");
    }

    @Test
    void whenApiIsTombstoned_thenSyncCleansUpItsConfluencePageAndNullsTheId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("{\"id\":\"NEW_SVC_PAGE\"}")));

        Service s = createService("orphan-endpoint-svc");
        java.util.UUID liveId = relationships.insertApi(s.getId(), "/v1/legacy", "GET",
                null, "Legacy", "openapi");
        relationships.setApiConfluencePageId(liveId, "DOOMED_EP");

        // Append a tombstone (mirrors what code-sync does when the spec drops the endpoint).
        java.util.UUID tombstoneId = relationships.writeApiTombstone(s.getId(),
                "GET", "/v1/legacy", "openapi", "DOOMED_EP");

        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/DOOMED_EP"))
                .willReturn(aResponse().withStatus(204)));

        coordinator.syncAll();

        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/DOOMED_EP")));
        // The tombstone's confluence_page_id has been nulled by the cleanup.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE id = ? AND presence = 'absent' " +
                        "AND confluence_page_id IS NULL",
                Long.class, tombstoneId);
        assertThat(count).isEqualTo(1L);
        // The original live row is still present, untouched (append-only).
        Long liveStillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE id = ?",
                Long.class, liveId);
        assertThat(liveStillThere).isEqualTo(1L);

        // Re-running sync is a no-op on this api row (idempotent).
        wireMock.resetRequests();
        coordinator.syncAll();
        wireMock.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v2/pages/DOOMED_EP")));
    }

    @Test
    void whenCleanupHits404OnAlreadyDeletedEndpointPage_thenStillNullsOutPageIdOnTombstone() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("{\"id\":\"NEW_SVC_PAGE\"}")));

        Service s = createService("ghost-endpoint-svc");
        java.util.UUID liveId = relationships.insertApi(s.getId(), "/v1/x", "GET",
                null, "x", "openapi");
        relationships.setApiConfluencePageId(liveId, "GHOST_EP");
        java.util.UUID tombstoneId = relationships.writeApiTombstone(s.getId(),
                "GET", "/v1/x", "openapi", "GHOST_EP");

        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/GHOST_EP"))
                .willReturn(aResponse().withStatus(404)));

        coordinator.syncAll();

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE id = ? AND confluence_page_id IS NULL",
                Long.class, tombstoneId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenApiHasTombstoneAsLatestObservation_thenItIsHiddenFromLiveViewButRowsSurvive() {
        Service s = createService("hidden-svc");
        java.util.UUID liveId = relationships.insertApi(s.getId(), "/v1/live", "GET",
                null, "live", "intake");
        java.util.UUID deadId = relationships.insertApi(s.getId(), "/v1/dead", "GET",
                null, "dead", "openapi");

        java.util.UUID tombstoneId = relationships.writeApiTombstone(s.getId(),
                "GET", "/v1/dead", "openapi", null);

        // Hidden from the live-only read (used by renderer + MCP).
        java.util.List<ApiSummary> live = relationships.findApisFor(s.getId());
        assertThat(live).extracting(ApiSummary::id).containsExactly(liveId);

        // Both observations survive in the table — the original "present"
        // observation and the tombstone are immutable history.
        Long deadCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE id = ?", Long.class, deadId);
        Long tombstoneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE id = ? AND presence = 'absent'",
                Long.class, tombstoneId);
        assertThat(deadCount).isEqualTo(1L);
        assertThat(tombstoneCount).isEqualTo(1L);
    }

    @Test
    void whenServiceHasTestScenarios_thenSyncCreatesTestsPageParentedUnderServicePage() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: tested-svc")))
                .willReturn(okJson("{\"id\":\"SVC_PAGE\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("tested-svc — Tests")))
                .willReturn(okJson("{\"id\":\"TESTS_PAGE\"}")));

        Service s = createService("tested-svc");
        relationships.insertTestScenario(s.getId(), "com.example", "Spec", "scenarioOne", "tests");
        relationships.insertTestScenario(s.getId(), "com.example", "Spec", "scenarioTwo", "tests");

        coordinator.syncOne(s.getId());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("tested-svc — Tests")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo("SVC_PAGE")))
                .withRequestBody(matchingJsonPath("$.body.value",
                        containing("scenarioOne"))));

        Service reloaded = serviceRepository.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getTestsPageId()).isEqualTo("TESTS_PAGE");
    }

    @Test
    void whenServiceAlreadyHasTestsPageId_thenSyncUpdatesItRatherThanCreating() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/SVC_OK"))
                .willReturn(okJson("{\"id\":\"SVC_OK\",\"version\":{\"number\":1}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/SVC_OK"))
                .willReturn(okJson("{\"id\":\"SVC_OK\",\"version\":{\"number\":2}}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/TESTS_OK"))
                .willReturn(okJson("{\"id\":\"TESTS_OK\",\"version\":{\"number\":3}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/TESTS_OK"))
                .willReturn(okJson("{\"id\":\"TESTS_OK\",\"version\":{\"number\":4}}")));

        Service s = new Service();
        s.setName("settled-tests-svc");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("SVC_OK");
        s.setTestsPageId("TESTS_OK");
        s = serviceRepository.save(s);
        relationships.insertTestScenario(s.getId(), "p", "Spec", "scenario", "tests");

        coordinator.syncOne(s.getId());

        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/TESTS_OK"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("settled-tests-svc — Tests"))));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("settled-tests-svc — Tests"))));
    }

    @Test
    void whenServiceHasNoTestScenarios_thenTestsPageIsStillRenderedWithThinNote() {
        // A service with no scenarios still gets a "Tests" page so the sidebar
        // tree is consistent. The page just notes "no scenarios documented".
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: empty-tests-svc")))
                .willReturn(okJson("{\"id\":\"SVC_PAGE\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("empty-tests-svc — Tests")))
                .willReturn(okJson("{\"id\":\"TESTS_PAGE\"}")));

        Service s = createService("empty-tests-svc");

        coordinator.syncOne(s.getId());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("empty-tests-svc — Tests")))
                .withRequestBody(matchingJsonPath("$.body.value",
                        containing("No test scenarios documented yet"))));
    }

    // ---- Phase 5.6 M2: per-module page lifecycle ------------------------

    @Test
    void whenServiceHasModuleRows_thenEachModuleGetsItsOwnPageParentedUnderTheServicePage() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Service: billing-svc")))
                .willReturn(okJson("{\"id\":\"SVC_PAGE\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("billing-svc — Module: (root)")))
                .willReturn(okJson("{\"id\":\"MOD_ROOT\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("billing-svc — Module: billing-api")))
                .willReturn(okJson("{\"id\":\"MOD_API\"}")));
        // Pass-2 update of module pages once page ids are known: stub PUT.
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/MOD_ROOT"))
                .willReturn(okJson("{\"id\":\"MOD_ROOT\",\"version\":{\"number\":2}}")));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/MOD_API"))
                .willReturn(okJson("{\"id\":\"MOD_API\",\"version\":{\"number\":2}}")));

        Service s = createService("billing-svc");
        relationships.insertModule(s.getId(), "", null,
                "com.example", "billing-svc", "1.0", "pom",
                null, null, null, "[]", "pom-xml");
        relationships.insertModule(s.getId(), "billing-api", "",
                "com.example", "billing-api", "1.0", "jar",
                null, null, null, "[]", "pom-xml");

        coordinator.syncOne(s.getId());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("billing-svc — Module: (root)")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo("SVC_PAGE"))));
        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title",
                        equalTo("billing-svc — Module: billing-api")))
                .withRequestBody(matchingJsonPath("$.parentId", equalTo("SVC_PAGE"))));

        java.util.List<com.atlas.services.ServiceModule> live =
                relationships.findModulesFor(s.getId());
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        for (com.atlas.services.ServiceModule m : live) {
            ids.put(m.modulePath(), m.confluencePageId());
        }
        assertThat(ids).containsEntry("", "MOD_ROOT");
        assertThat(ids).containsEntry("billing-api", "MOD_API");
    }

    @Test
    void whenModuleIsTombstoned_thenSyncCleansUpItsConfluencePageAndNullsTheId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("{\"results\":[{\"id\":\"589827\",\"key\":\"ATLAS\"}]}")));
        stubLandingPageExists();
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("{\"id\":\"NEW_SVC_PAGE\"}")));

        Service s = createService("orphan-mod-svc");
        java.util.UUID liveObs = relationships.insertModule(s.getId(),
                "doomed", "", "com.example", "doomed", "1.0", "jar",
                null, null, null, "[]", "pom-xml");
        relationships.setModuleConfluencePageId(liveObs, "DOOMED_MOD");
        java.util.UUID tombstoneId = relationships.writeModuleTombstone(s.getId(),
                "doomed", "pom-xml", "DOOMED_MOD");

        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/DOOMED_MOD"))
                .willReturn(aResponse().withStatus(204)));

        coordinator.syncAll();

        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/DOOMED_MOD")));
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_modules WHERE id = ? AND presence = 'absent' " +
                        "AND confluence_page_id IS NULL",
                Long.class, tombstoneId);
        assertThat(count).isEqualTo(1L);
    }

    private Service createService(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return serviceRepository.save(s);
    }
}
