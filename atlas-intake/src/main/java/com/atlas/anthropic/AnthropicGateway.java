package com.atlas.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnthropicGateway {

    private final AnthropicClient sdkClient;
    private final String model;

    public AnthropicGateway(
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.model:claude-sonnet-4-6}") String model) {
        this.sdkClient = (apiKey == null || apiKey.isBlank())
                ? null
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    public String complete(String prompt) {
        if (sdkClient == null) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not configured; set the env var to enable Anthropic calls");
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
                .orElseThrow(() -> new IllegalStateException("No text content in Anthropic response"));
    }
}
