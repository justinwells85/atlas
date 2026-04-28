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

import java.util.HashMap;
import java.util.Map;

import static com.atlas.mcp.McpClientTestSupport.call;
import static com.atlas.mcp.McpClientTestSupport.firstText;
import static com.atlas.mcp.McpClientTestSupport.withClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ListServicesContractTest {

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
    void seed() {
        // Hard-DELETE — repository.deleteAll() now soft-deletes (V11/ADR-014).
        jdbc.update("DELETE FROM services");
        for (int i = 1; i <= 5; i++) {
            Service s = new Service();
            s.setName(String.format("svc-%02d", i));
            s.setOwnerTeam("team-" + ((i % 2) + 1));
            s.setStatus(ServiceStatus.ACTIVE);
            services.save(s);
        }
    }

    @Test
    void whenPagingNotSpecified_thenReturnsFirstDefaultPageWithAllRowsAndTotal() throws Exception {
        JsonNode body = callList(Map.of());

        assertThat(body.get("page").asInt()).isEqualTo(0);
        assertThat(body.get("pageSize").asInt()).isEqualTo(20);
        assertThat(body.get("totalCount").asLong()).isEqualTo(5);
        assertThat(body.get("services")).hasSize(5);
        assertThat(body.get("services").get(0).get("name").asText()).isEqualTo("svc-01");
        assertThat(body.get("services").get(4).get("name").asText()).isEqualTo("svc-05");
    }

    @Test
    void whenSecondPageRequested_thenReturnsThatSlice() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("page", 1);
        args.put("pageSize", 2);

        JsonNode body = callList(args);

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("pageSize").asInt()).isEqualTo(2);
        assertThat(body.get("totalCount").asLong()).isEqualTo(5);
        assertThat(body.get("services")).hasSize(2);
        assertThat(body.get("services").get(0).get("name").asText()).isEqualTo("svc-03");
        assertThat(body.get("services").get(1).get("name").asText()).isEqualTo("svc-04");
    }

    private JsonNode callList(Map<String, Object> args) {
        return withClient(port, client -> {
            try {
                return json.readTree(firstText(call(client, "listServices", args)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
