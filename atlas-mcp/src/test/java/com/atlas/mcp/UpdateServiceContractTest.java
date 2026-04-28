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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.atlas.mcp.McpClientTestSupport.call;
import static com.atlas.mcp.McpClientTestSupport.firstText;
import static com.atlas.mcp.McpClientTestSupport.withClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UpdateServiceContractTest {

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
        jdbc.update("DELETE FROM services");  // hard-DELETE (V11/ADR-014 soft-deletes by default)
    }

    @Test
    void whenFieldsAreProvided_thenChangesPersist() throws Exception {
        Service saved = services.saveAndFlush(svc("payments-api", "payments", ServiceStatus.IN_DEV));

        Map<String, Object> args = new HashMap<>();
        args.put("serviceId", saved.getId().toString());
        args.put("description", "Handles checkout payments end-to-end.");
        args.put("status", "active");
        args.put("language", "Java");
        args.put("framework", "Spring Boot");

        JsonNode body = update(args);

        assertThat(body.get("description").asText()).isEqualTo("Handles checkout payments end-to-end.");
        assertThat(body.get("status").asText()).isEqualTo("active");
        assertThat(body.get("language").asText()).isEqualTo("Java");
        assertThat(body.get("framework").asText()).isEqualTo("Spring Boot");

        Service reloaded = services.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDescription()).isEqualTo("Handles checkout payments end-to-end.");
        assertThat(reloaded.getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(reloaded.getLanguage()).isEqualTo("Java");
        assertThat(reloaded.getFramework()).isEqualTo("Spring Boot");
        // Untouched fields stay put.
        assertThat(reloaded.getName()).isEqualTo("payments-api");
        assertThat(reloaded.getOwnerTeam()).isEqualTo("payments");
    }

    @Test
    void whenSomethingChanges_thenUpdatedAtMovesAheadOfCreatedAt() throws Exception {
        // Service.createdAt/updatedAt are insertable=false, so the in-memory
        // entity returned by saveAndFlush has them as null. Reload to populate.
        Service initial = services.saveAndFlush(svc("inventory-svc", "inventory", ServiceStatus.ACTIVE));
        Thread.sleep(5);

        update(Map.of(
                "serviceId", initial.getId().toString(),
                "notes", "now has notes"));

        Service reloaded = services.findById(initial.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isAfter(reloaded.getCreatedAt());
    }

    @Test
    void whenPatchIsEmpty_thenServiceIsReturnedUnchangedAndUpdatedAtDoesNotMove() throws Exception {
        Service initial = services.saveAndFlush(svc("orders-api", "orders", ServiceStatus.ACTIVE));
        OffsetDateTime originalUpdatedAt =
                services.findById(initial.getId()).orElseThrow().getUpdatedAt();
        Thread.sleep(5);

        JsonNode body = update(Map.of("serviceId", initial.getId().toString()));

        assertThat(body.get("name").asText()).isEqualTo("orders-api");
        Service reloaded = services.findById(initial.getId()).orElseThrow();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void whenServiceIdIsUnknown_thenReturnsErrorResult() {
        CallToolResult result = withClient(port, client ->
                call(client, "updateService", Map.of(
                        "serviceId", UUID.randomUUID().toString(),
                        "description", "anything")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void whenStatusValueIsInvalid_thenReturnsErrorResult() {
        Service saved = services.saveAndFlush(svc("legacy-billing", "platform", ServiceStatus.ACTIVE));

        CallToolResult result = withClient(port, client ->
                call(client, "updateService", Map.of(
                        "serviceId", saved.getId().toString(),
                        "status", "totally-bogus")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void whenRenamingToANameAlreadyTaken_thenReturnsErrorResult() {
        services.saveAndFlush(svc("payments-api", "payments", ServiceStatus.ACTIVE));
        Service victim = services.saveAndFlush(svc("payments-api-v2", "payments", ServiceStatus.ACTIVE));

        CallToolResult result = withClient(port, client ->
                call(client, "updateService", Map.of(
                        "serviceId", victim.getId().toString(),
                        "name", "payments-api")));
        assertThat(result.isError()).isTrue();

        Service reloaded = services.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("payments-api-v2");
    }

    @Test
    void whenMetadataIsProvided_thenItReplacesTheStoredMap() throws Exception {
        Service initial = svc("metrics-svc", "platform", ServiceStatus.ACTIVE);
        initial.setMetadata(Map.of("tier", "bronze", "team", "old"));
        Service saved = services.saveAndFlush(initial);

        Map<String, Object> args = new HashMap<>();
        args.put("serviceId", saved.getId().toString());
        args.put("metadata", Map.of("tier", "gold", "compliance", "soc2"));

        JsonNode body = update(args);

        assertThat(body.get("metadata").get("tier").asText()).isEqualTo("gold");
        assertThat(body.get("metadata").get("compliance").asText()).isEqualTo("soc2");
        // Old keys are gone — replace, not merge.
        assertThat(body.get("metadata").has("team")).isFalse();
    }

    @Test
    void whenSomethingChanges_thenServiceChangeAuditRowIsWritten() throws Exception {
        Service saved = services.saveAndFlush(svc("orders-api", "orders", ServiceStatus.ACTIVE));

        update(Map.of(
                "serviceId", saved.getId().toString(),
                "language", "Java",
                "framework", "Spring Boot"));

        java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT change_type, changed_by, summary FROM service_changes WHERE service_id = ?",
                saved.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("change_type")).isEqualTo("updated");
        assertThat(rows.get(0).get("changed_by")).isEqualTo("mcp-update_service");
        String summary = (String) rows.get(0).get("summary");
        assertThat(summary).contains("language").contains("framework");
    }

    @Test
    void whenPatchIsEmpty_thenNoServiceChangeAuditRowIsWritten() throws Exception {
        Service saved = services.saveAndFlush(svc("orders-api", "orders", ServiceStatus.ACTIVE));

        update(Map.of("serviceId", saved.getId().toString()));

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_changes WHERE service_id = ?",
                Long.class, saved.getId());
        assertThat(count).isEqualTo(0L);
    }

    private JsonNode update(Map<String, Object> args) throws Exception {
        String text = withClient(port, client ->
                firstText(call(client, "updateService", args)));
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
