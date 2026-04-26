package com.atlas.intake;

import com.atlas.anthropic.AnthropicGateway;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/smoke")
public class SmokeController {

    private final AnthropicGateway anthropic;

    public SmokeController(AnthropicGateway anthropic) {
        this.anthropic = anthropic;
    }

    @PostMapping("/anthropic")
    public SmokeResponse smoke(@RequestBody SmokeRequest request) {
        String text = anthropic.complete(request.prompt());
        return new SmokeResponse(text);
    }

    public record SmokeRequest(String prompt) {}
    public record SmokeResponse(String response) {}
}
