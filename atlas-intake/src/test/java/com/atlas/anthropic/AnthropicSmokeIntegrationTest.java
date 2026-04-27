package com.atlas.anthropic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-API smoke test for the anthropic-java SDK integration.
 *
 * Skipped (not failed) when ANTHROPIC_API_KEY is absent — keeps CI green
 * while letting a local developer with a key prove the integration works.
 */
class AnthropicSmokeIntegrationTest {

    @Test
    void whenSmokePromptIsSent_thenAnthropicReturnsNonEmptyResponse() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY not set; skipping real-API smoke");

        AnthropicGateway gateway = new AnthropicGateway(apiKey, "claude-sonnet-4-6");

        String response = gateway.complete("Reply with just the word 'pong' and nothing else.");

        assertThat(response).isNotBlank();
    }
}
