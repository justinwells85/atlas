package com.atlas.intake;

import com.atlas.llm.LlmGateway;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/smoke")
public class SmokeController {

    private final LlmGateway llm;

    public SmokeController(LlmGateway llm) {
        this.llm = llm;
    }

    @PostMapping("/llm")
    public SmokeResponse smoke(@RequestBody SmokeRequest request) {
        String text = llm.complete(request.prompt());
        return new SmokeResponse(text);
    }

    public record SmokeRequest(String prompt) {}
    public record SmokeResponse(String response) {}
}
