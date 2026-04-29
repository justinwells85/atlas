package com.atlas.mcp;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.atlas.mcp.McpClientTestSupport.call;
import static com.atlas.mcp.McpClientTestSupport.firstText;
import static com.atlas.mcp.McpClientTestSupport.withClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DeleteServiceContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @LocalServerPort
    int port;

    @Autowired
    ServiceRepository services;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wipe() {
        // service_changes has a soft FK on service_id (no cascade); clean it
        // explicitly so audit-row tests see a fresh slate.
        jdbc.update("DELETE FROM service_changes");
        jdbc.update("DELETE FROM services");  // hard-DELETE for test isolation
    }

    @Test
    void whenDeleteServiceCalled_thenServiceIsSoftDeleted() throws Exception {
        Service saved = services.saveAndFlush(svc("retired-svc", "platform", ServiceStatus.DEPRECATED));
        UUID id = saved.getId();

        JsonNode body = delete(Map.of("serviceId", id.toString()));

        // Returned body matches ServiceDetails-shape; deletedAt must be set.
        assertThat(body.get("id").asText()).isEqualTo(id.toString());
        assertThat(body.get("name").asText()).isEqualTo("retired-svc");
        assertThat(body.has("deletedAt")).isTrue();
        assertThat(body.get("deletedAt").isNull()).isFalse();

        // The row stays in the DB but is hidden from JPA queries.
        assertThat(services.findById(id)).isEmpty();
        // Confirm the soft-delete via a native lookup that bypasses @SQLRestriction.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT deleted_at FROM services WHERE id = ?", id);
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    void whenDeleteServiceCalled_thenServiceChangeAuditRowIsWritten() throws Exception {
        Service saved = services.saveAndFlush(svc("audit-svc", "platform", ServiceStatus.ACTIVE));

        delete(Map.of("serviceId", saved.getId().toString()));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT change_type, changed_by, summary FROM service_changes WHERE service_id = ?",
                saved.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("change_type")).isEqualTo("deleted");
        assertThat(rows.get(0).get("changed_by")).isEqualTo("mcp-delete_service");
    }

    @Test
    void whenServiceAlreadySoftDeleted_thenReturnsErrorResult() {
        Service saved = services.saveAndFlush(svc("once-svc", "platform", ServiceStatus.ACTIVE));
        services.delete(saved);  // soft-delete via @SQLDelete

        CallToolResult result = withClient(port, client ->
                call(client, "deleteService", Map.of("serviceId", saved.getId().toString())));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void whenServiceIdIsUnknown_thenReturnsErrorResult() {
        CallToolResult result = withClient(port, client ->
                call(client, "deleteService", Map.of("serviceId", UUID.randomUUID().toString())));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void whenServiceIdIsMalformed_thenReturnsErrorResult() {
        CallToolResult result = withClient(port, client ->
                call(client, "deleteService", Map.of("serviceId", "not-a-uuid")));
        assertThat(result.isError()).isTrue();
    }

    private JsonNode delete(Map<String, Object> args) throws Exception {
        String text = withClient(port, client ->
                firstText(call(client, "deleteService", args)));
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
