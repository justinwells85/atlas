package com.atlas.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-API smoke test for the {@link AnthropicLlmGateway} provider.
 *
 * Skipped (not failed) when ANTHROPIC_API_KEY is absent — keeps CI green
 * while letting a local developer with a key prove the integration works.
 */
class AnthropicLlmGatewayIntegrationTest {

    @Test
    void whenSmokePromptIsSent_thenLlmReturnsNonEmptyResponse() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY not set; skipping real-API smoke");

        LlmGateway gateway = new AnthropicLlmGateway(apiKey, "claude-sonnet-4-6");

        String response = gateway.complete("Reply with just the word 'pong' and nothing else.");

        assertThat(response).isNotBlank();
    }
}
