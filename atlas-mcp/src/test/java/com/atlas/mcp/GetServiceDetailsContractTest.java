package com.atlas.mcp;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static com.atlas.mcp.McpClientTestSupport.call;
import static com.atlas.mcp.McpClientTestSupport.firstText;
import static com.atlas.mcp.McpClientTestSupport.withClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GetServiceDetailsContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @LocalServerPort
    int port;

    @Autowired
    ServiceRepository services;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wipe() {
        // Clean every relationship table, then services. FK cascade catches the rest.
        jdbc.update("DELETE FROM service_external_deps");
        jdbc.update("DELETE FROM service_databases");
        jdbc.update("DELETE FROM service_dependencies");
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM external_dependencies");
        jdbc.update("DELETE FROM data_stores");
        jdbc.update("DELETE FROM services");  // hard-DELETE (V11/ADR-014 soft-deletes by default)
        jdbc.update("DELETE FROM service_changes");
    }

    @Test
    void whenServiceHasNoRelationships_thenReturnsRowFieldsAndEmptyArrays() throws Exception {
        Service saved = services.save(svc("checkout-svc", "checkout", ServiceStatus.ACTIVE));

        JsonNode body = callDetails(saved.getId().toString());

        assertThat(body.get("id").asText()).isEqualTo(saved.getId().toString());
        assertThat(body.get("name").asText()).isEqualTo("checkout-svc");
        assertThat(body.get("ownerTeam").asText()).isEqualTo("checkout");
        assertThat(body.get("status").asText()).isEqualTo("active");
        assertThat(body.get("apis").isArray()).isTrue();
        assertThat(body.get("apis")).isEmpty();
        assertThat(body.get("upstreamDependencies")).isEmpty();
        assertThat(body.get("downstreamDependencies")).isEmpty();
        assertThat(body.get("databases")).isEmpty();
        assertThat(body.get("externalDependencies")).isEmpty();
    }

    @Test
    void whenServiceHasRelationships_thenJoinedRowsAppearInResponse() throws Exception {
        Service main = services.save(svc("orders-api", "orders", ServiceStatus.ACTIVE));
        Service upstream = services.save(svc("inventory-api", "inventory", ServiceStatus.ACTIVE));
        Service downstream = services.save(svc("checkout-ui", "checkout", ServiceStatus.ACTIVE));

        // APIs exposed by main
        jdbc.update("INSERT INTO apis (id, service_id, path, method, auth_method, description) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), "/v1/orders", "POST", "api-key", "Place an order");
        jdbc.update("INSERT INTO apis (id, service_id, path, method, auth_method, description) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), "/v1/orders/{id}", "GET", "api-key", "Read an order");

        // main depends on upstream; downstream depends on main
        jdbc.update("INSERT INTO service_dependencies (id, upstream_service_id, downstream_service_id, description) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), upstream.getId(), main.getId(), "checks stock");
        jdbc.update("INSERT INTO service_dependencies (id, upstream_service_id, downstream_service_id, description) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), downstream.getId(), "places orders");

        // databases: main owns one, uses another
        UUID ownedDb = UUID.randomUUID();
        UUID sharedDb = UUID.randomUUID();
        jdbc.update("INSERT INTO data_stores (id, name, engine) VALUES (?, ?, ?)", ownedDb, "orders-db", "postgres");
        jdbc.update("INSERT INTO data_stores (id, name, engine) VALUES (?, ?, ?)", sharedDb, "shared-cache", "redis");
        jdbc.update("INSERT INTO service_databases (id, service_id, database_id, is_owner, description) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), ownedDb, true, "primary store");
        jdbc.update("INSERT INTO service_databases (id, service_id, database_id, is_owner, description) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), sharedDb, false, "session cache");

        // external deps
        UUID stripeId = UUID.randomUUID();
        jdbc.update("INSERT INTO external_dependencies (id, name, url, description) VALUES (?, ?, ?, ?)",
                stripeId, "Stripe", "https://stripe.com", "card payments");
        jdbc.update("INSERT INTO service_external_deps (id, service_id, external_dependency_id, description) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), main.getId(), stripeId, "card payment processing");

        JsonNode body = callDetails(main.getId().toString());

        assertThat(body.get("apis")).hasSize(2);
        assertThat(body.get("apis").get(0).get("path").asText()).isEqualTo("/v1/orders");
        assertThat(body.get("apis").get(1).get("path").asText()).isEqualTo("/v1/orders/{id}");

        assertThat(body.get("upstreamDependencies")).hasSize(1);
        assertThat(body.get("upstreamDependencies").get(0).get("upstreamServiceName").asText())
                .isEqualTo("inventory-api");

        assertThat(body.get("downstreamDependencies")).hasSize(1);
        assertThat(body.get("downstreamDependencies").get(0).get("downstreamServiceName").asText())
                .isEqualTo("checkout-ui");

        assertThat(body.get("databases")).hasSize(2);
        // ordered by name: orders-db, shared-cache
        assertThat(body.get("databases").get(0).get("databaseName").asText()).isEqualTo("orders-db");
        assertThat(body.get("databases").get(0).get("isOwner").asBoolean()).isTrue();
        assertThat(body.get("databases").get(1).get("databaseName").asText()).isEqualTo("shared-cache");
        assertThat(body.get("databases").get(1).get("isOwner").asBoolean()).isFalse();

        assertThat(body.get("externalDependencies")).hasSize(1);
        assertThat(body.get("externalDependencies").get(0).get("name").asText()).isEqualTo("Stripe");
    }

    @Test
    void whenServiceIdIsUnknown_thenReturnsErrorResult() {
        var result = withClient(port, client ->
                call(client, "getServiceDetails", Map.of("serviceId", UUID.randomUUID().toString())));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void whenServiceIdIsNotAUuid_thenReturnsErrorResult() {
        var result = withClient(port, client ->
                call(client, "getServiceDetails", Map.of("serviceId", "not-a-uuid")));
        assertThat(result.isError()).isTrue();
    }

    private JsonNode callDetails(String serviceId) throws Exception {
        String text = withClient(port, client ->
                firstText(call(client, "getServiceDetails", Map.of("serviceId", serviceId))));
        return json.readTree(text);
    }

    private static Service svc(String name, String ownerTeam, ServiceStatus status) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(status);
        return s;
    }
}
