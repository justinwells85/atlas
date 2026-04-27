package com.atlas.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Atlas's tool-bearing components into a single {@link ToolCallbackProvider}
 * bean. Spring AI's MCP server autoconfig discovers this bean and exposes every
 * {@code @Tool}-annotated method on the listed objects as an MCP tool.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    ToolCallbackProvider atlasMcpTools(PingTool pingTool, ServiceTools serviceTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pingTool, serviceTools)
                .build();
    }
}
