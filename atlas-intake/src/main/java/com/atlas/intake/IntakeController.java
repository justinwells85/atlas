package com.atlas.intake;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final InterviewService interviewService;
    private final RemovalService removalService;

    public IntakeController(InterviewService interviewService, RemovalService removalService) {
        this.interviewService = interviewService;
        this.removalService = removalService;
    }

    @PostMapping("/turn")
    @Operation(summary = "Advance the AI-assisted intake interview by one turn",
            description = "Multi-turn endpoint per ADR-011: prior conversation state rides in the request body and the response carries the next state, the next prompt, a complete flag, and a persisted serviceId once the interview finishes.")
    public InterviewService.TurnResult turn(@RequestBody IntakeTurnRequest request) {
        return interviewService.next(request.state(), request.userInput());
    }

    @PostMapping("/remove")
    @Operation(summary = "Soft-delete an existing service via a two-stage confirmation flow",
            description = "Asks for the service name, then echoes back name + owner team and waits for yes/no. On yes, soft-deletes per ADR-014 and audits change_type=deleted, changed_by=intake-removal.")
    public RemovalService.RemovalResult remove(@RequestBody RemoveTurnRequest request) {
        return removalService.next(request.state(), request.userInput());
    }

    public record IntakeTurnRequest(InterviewState state, String userInput) {}

    public record RemoveTurnRequest(RemovalState state, String userInput) {}
}
