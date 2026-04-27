package com.atlas.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the Atlas MCP server. Drives the server through the real
 * MCP wire protocol (HTTP/SSE) using the official MCP Java SDK client — not by
 * calling Java methods directly. A green run proves: the server boots, the SSE
 * transport publishes its endpoints, the tool-callback registration is wired
 * into the MCP tool surface, and a client can complete a call_tool round-trip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PingToolContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @LocalServerPort
    int port;

    @Test
    void whenPingToolIsInvoked_thenReturnsPong() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:" + port)
                .build();

        try (McpSyncClient client = McpClient.sync(transport).build()) {
            client.initialize();

            CallToolResult result = client.callTool(
                    CallToolRequest.builder().name("ping").arguments(Map.of()).build());

            assertThat(result.isError()).isFalse();
            assertThat(result.content()).hasSize(1);
            // Spring AI's MethodToolCallback JSON-serializes Java String returns,
            // so the TextContent body is "\"pong\"" rather than the bare word.
            // Assert on the substring so the test stays robust to that choice.
            assertThat(((TextContent) result.content().get(0)).text()).contains("pong");
        }
    }
}
