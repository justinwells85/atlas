package com.atlas.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link LlmGateway} backed by the {@code anthropic-java} SDK. Active when
 * {@code atlas.llm.provider=anthropic} (the default).
 */
@Component
@ConditionalOnProperty(prefix = "atlas.llm", name = "provider",
        havingValue = "anthropic", matchIfMissing = true)
public class AnthropicLlmGateway implements LlmGateway {

    private final AnthropicClient sdkClient;
    private final String model;

    public AnthropicLlmGateway(
            @Value("${atlas.llm.anthropic.api-key:}") String apiKey,
            @Value("${atlas.llm.anthropic.model:claude-sonnet-4-6}") String model) {
        this.sdkClient = (apiKey == null || apiKey.isBlank())
                ? null
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        if (sdkClient == null) {
            throw new IllegalStateException(
                    "atlas.llm.anthropic.api-key is not configured (env var ANTHROPIC_API_KEY); " +
                            "set it to enable LLM calls or switch atlas.llm.provider.");
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .addUserMessage(prompt)
                .build();
        Message response = sdkClient.messages().create(params);
        return response.content().stream()
                .filter(block -> block.text().isPresent())
                .map(block -> block.text().get().text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text content in LLM response"));
    }
}
