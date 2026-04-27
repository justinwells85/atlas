package com.atlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas MCP server. Exposes service inventory data over the Model Context
 * Protocol via HTTP/SSE for AI clients (Claude Desktop, the Phase 4 Confluence
 * Sync Agent, and any other MCP-compatible consumer).
 *
 * Lives at the {@code com.atlas} root so default Spring Boot scanning picks
 * up both this module's tools ({@code com.atlas.mcp}) and the shared JPA
 * entities and repositories from atlas-domain ({@code com.atlas.services}).
 */
@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
