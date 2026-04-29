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

    private Service save(String name, String ownerTeam) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(ServiceStatus.ACTIVE);
        return services.saveAndFlush(s);
    }
}
