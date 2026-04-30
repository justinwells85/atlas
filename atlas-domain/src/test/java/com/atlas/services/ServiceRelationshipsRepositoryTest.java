package com.atlas.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ServiceRelationshipsRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    ServiceRepository services;

    @Autowired
    ServiceRelationshipsRepository relationships;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        // Clean both tables; service_dependencies cascades on services delete
        // but we want a deterministic empty starting state.
        jdbc.update("DELETE FROM service_dependencies");
        jdbc.update("DELETE FROM service_changes");
        jdbc.update("DELETE FROM services");
    }

    @Test
    void whenNoEdgesExist_thenFindAllReturnsEmpty() {
        assertThat(relationships.findAllServiceDependencies()).isEmpty();
    }

    @Test
    void whenEdgesExist_thenFindAllReturnsThemWithBothEndpointNames() {
        Service upstream = save("upstream-svc", "platform");
        Service downstream = save("downstream-svc", "platform");
        relationships.insertServiceDependency(upstream.getId(), downstream.getId(), "calls REST");

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamServiceName()).isEqualTo("upstream-svc");
        assertThat(edges.get(0).downstreamServiceName()).isEqualTo("downstream-svc");
        assertThat(edges.get(0).description()).isEqualTo("calls REST");
    }

    @Test
    void whenEitherEndpointIsSoftDeleted_thenEdgeIsExcluded() {
        Service a = save("active-a", "team");
        Service b = save("active-b", "team");
        Service c = save("doomed-c", "team");
        relationships.insertServiceDependency(a.getId(), b.getId(), "live edge");
        relationships.insertServiceDependency(a.getId(), c.getId(), "edge to doomed downstream");
        relationships.insertServiceDependency(c.getId(), b.getId(), "edge from doomed upstream");

        // Soft-delete c — both edges touching it should drop out.
        services.delete(c);

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamServiceName()).isEqualTo("active-a");
        assertThat(edges.get(0).downstreamServiceName()).isEqualTo("active-b");
    }

    @Test
    void whenMultipleEdges_thenResultIsSortedByUpstreamThenDownstream() {
        Service a = save("aaa-svc", "team");
        Service b = save("bbb-svc", "team");
        Service c = save("ccc-svc", "team");
        // Insert out of order to verify ordering at the SQL layer.
        relationships.insertServiceDependency(c.getId(), b.getId(), null);
        relationships.insertServiceDependency(a.getId(), c.getId(), null);
        relationships.insertServiceDependency(a.getId(), b.getId(), null);

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).extracting(ServiceDependencyEdge::upstreamServiceName,
                        ServiceDependencyEdge::downstreamServiceName)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("aaa-svc", "bbb-svc"),
                        org.assertj.core.api.Assertions.tuple("aaa-svc", "ccc-svc"),
                        org.assertj.core.api.Assertions.tuple("ccc-svc", "bbb-svc"));
    }

    // ---- M4.5: external-deps live-view + stale-intake-apis -----------------

    @Test
    void findExternalDependenciesFor_excludesTombstonedObservationsAndExposesSource() {
        Service svc = save("composed-svc", "team");
        UUID intakeDepId = relationships.insertExternalDependency("Stripe", "https://stripe.com");
        relationships.insertServiceExternalDepObservation(
                svc.getId(), intakeDepId, "Payment processor", "intake");

        UUID pomDepId = relationships.insertExternalDependency("com.fasterxml.jackson.core:jackson-databind", null);
        relationships.insertServiceExternalDepObservation(
                svc.getId(), pomDepId, null, "pom-xml");

        // Tombstoned pom-source observation should not appear.
        UUID disappearedDepId = relationships.insertExternalDependency("org.gone:gone-artifact", null);
        relationships.insertServiceExternalDepObservation(
                svc.getId(), disappearedDepId, null, "pom-xml");
        relationships.writeServiceExternalDepTombstone(svc.getId(), disappearedDepId, "pom-xml");

        List<ExternalDependencyUsage> deps = relationships.findExternalDependenciesFor(svc.getId());

        assertThat(deps).extracting(ExternalDependencyUsage::name, ExternalDependencyUsage::source)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("Stripe", "intake"),
                        org.assertj.core.api.Assertions.tuple("com.fasterxml.jackson.core:jackson-databind", "pom-xml"));
        assertThat(deps).noneMatch(d -> d.name().equals("org.gone:gone-artifact"));
    }

    @Test
    void findExternalDependenciesFor_returnsLatestObservationPerSourceAfterMultipleRefreshes() {
        Service svc = save("repeated-svc", "team");
        UUID depId = relationships.insertExternalDependency("com.foo:bar", null);
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");

        List<ExternalDependencyUsage> deps = relationships.findExternalDependenciesFor(svc.getId());

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).source()).isEqualTo("pom-xml");
    }

    @Test
    void findStaleIntakeApis_returnsIntakeApisWithNoOpenapiCounterpart() {
        Service svc = save("stale-svc", "team");
        // Intake declared two endpoints. Code shipped /v1/orders openapi-source
        // but renamed /v1/legacy → /v1/legacy-renamed (so /v1/legacy is stale).
        relationships.insertApi(svc.getId(), "/v1/orders", "GET", null, "Order list", "intake");
        relationships.insertApi(svc.getId(), "/v1/legacy", "GET", null, "Legacy endpoint", "intake");
        relationships.insertApi(svc.getId(), "/v1/orders", "GET", null, "Orders (from spec)", "openapi");
        relationships.insertApi(svc.getId(), "/v1/legacy-renamed", "GET", null, "Renamed endpoint", "openapi");

        List<ApiSummary> stale = relationships.findStaleIntakeApis(svc.getId());

        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).method()).isEqualTo("GET");
        assertThat(stale.get(0).path()).isEqualTo("/v1/legacy");
        assertThat(stale.get(0).source()).isEqualTo("intake");
    }

    @Test
    void findStaleIntakeApis_emptyWhenNoIntakeApisExist() {
        Service svc = save("openapi-only", "team");
        relationships.insertApi(svc.getId(), "/v1/health", "GET", null, "ping", "openapi");

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void findStaleIntakeApis_excludesAlreadyTombstonedIntakeRows() {
        Service svc = save("already-cleaned", "team");
        relationships.insertApi(svc.getId(), "/v1/old", "GET", null, "removed", "intake");
        relationships.writeApiTombstone(svc.getId(), "GET", "/v1/old", "intake", null);

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void findStaleIntakeApis_doesNotIncludeIntakeRowsWithMatchingOpenapiObservation() {
        Service svc = save("aligned-svc", "team");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null, "Create order", "intake");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null, "Create order (spec)", "openapi");

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void whenApiIsInsertedWithSnapshot_thenLiveViewReturnsIt() {
        Service svc = save("snap-svc", "team");
        String snapshot = "{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\"}}}}}";

        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, snapshot);

        List<ApiSummary> rows = relationships.findApisFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).openapiSnapshot()).isEqualTo(snapshot);
    }

    @Test
    void whenLatestObservationCarriesSnapshot_thenLiveViewSurfacesIt_andEarlierIsHidden() {
        Service svc = save("snap-evolve", "team");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, "{\"v\":1}");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, "{\"v\":2}");

        List<ApiSummary> rows = relationships.findApisFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).openapiSnapshot()).isEqualTo("{\"v\":2}");
    }

    private Service save(String name, String ownerTeam) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(ServiceStatus.ACTIVE);
        return services.saveAndFlush(s);
    }
}
