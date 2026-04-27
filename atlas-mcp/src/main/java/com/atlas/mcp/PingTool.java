package com.atlas.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class PingTool {

    @Tool(description = "Health check. Returns 'pong' to confirm the Atlas MCP server is reachable and tool dispatch works end-to-end.")
    public String ping() {
        return "pong";
    }
}
