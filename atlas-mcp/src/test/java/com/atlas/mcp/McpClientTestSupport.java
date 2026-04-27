package com.atlas.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.Map;
import java.util.function.Function;

/**
 * Helpers shared by the MCP tool contract tests so each test stays focused on
 * "what the tool returned for these inputs" rather than transport plumbing.
 */
final class McpClientTestSupport {

    private McpClientTestSupport() {}

    static <T> T withClient(int port, Function<McpSyncClient, T> body) {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:" + port)
                .build();
        try (McpSyncClient client = McpClient.sync(transport).build()) {
            client.initialize();
            return body.apply(client);
        }
    }

    static CallToolResult call(McpSyncClient client, String toolName, Map<String, Object> args) {
        return client.callTool(CallToolRequest.builder().name(toolName).arguments(args).build());
    }

    static String firstText(CallToolResult result) {
        if (result.content().isEmpty() || !(result.content().get(0) instanceof TextContent text)) {
            throw new IllegalStateException("Expected first content block to be TextContent: " + result.content());
        }
        return text.text();
    }
}
