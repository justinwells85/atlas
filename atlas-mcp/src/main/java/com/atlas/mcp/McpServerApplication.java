package com.atlas.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas MCP server. Exposes service inventory data over the Model Context
 * Protocol via HTTP/SSE for AI clients (Claude Desktop, the Phase 4 Confluence
 * Sync Agent, and any other MCP-compatible consumer). Tool implementations
 * are scaffolded in Phase 3 milestones M1–M3; this shell exists so M0 can
 * verify the multi-module split builds end-to-end.
 *
 * Scans {@code com.atlas} so JPA entities and repositories from atlas-domain
 * (com.atlas.services) are picked up.
 */
@SpringBootApplication(scanBasePackages = "com.atlas")
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
