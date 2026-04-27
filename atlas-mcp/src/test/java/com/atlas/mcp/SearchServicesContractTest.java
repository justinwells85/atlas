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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static com.atlas.mcp.McpClientTestSupport.call;
import static com.atlas.mcp.McpClientTestSupport.firstText;
import static com.atlas.mcp.McpClientTestSupport.withClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SearchServicesContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @LocalServerPort
    int port;

    @Autowired
    ServiceRepository services;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        services.deleteAll();
        services.saveAll(java.util.List.of(
                svc("payments-api", "payments", ServiceStatus.ACTIVE),
                svc("payments-worker", "payments", ServiceStatus.ACTIVE),
                svc("orders-api", "orders", ServiceStatus.IN_DEV),
                svc("legacy-billing", "platform", ServiceStatus.DEPRECATED)));
    }

    @Test
    void whenQueryMatchesNameSubstring_thenReturnsMatchingServicesOnly() throws Exception {
        JsonNode body = withClient(port, client -> {
            try {
                return json.readTree(firstText(call(client, "searchServices", Map.of("query", "payments"))));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("name").asText()).isEqualTo("payments-api");
        assertThat(body.get(1).get("name").asText()).isEqualTo("payments-worker");
        assertThat(body.get(0).get("ownerTeam").asText()).isEqualTo("payments");
        assertThat(body.get(0).get("status").asText()).isEqualTo("active");
    }

    @Test
    void whenQueryIsCaseInsensitive_thenStillMatches() throws Exception {
        JsonNode body = withClient(port, client -> {
            try {
                return json.readTree(firstText(call(client, "searchServices", Map.of("query", "ORDERS"))));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("name").asText()).isEqualTo("orders-api");
    }

    @Test
    void whenQueryMatchesNothing_thenReturnsEmptyArray() throws Exception {
        JsonNode body = withClient(port, client -> {
            try {
                return json.readTree(firstText(call(client, "searchServices", Map.of("query", "no-such-service"))));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    private static Service svc(String name, String ownerTeam, ServiceStatus status) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(status);
        return s;
    }
}
