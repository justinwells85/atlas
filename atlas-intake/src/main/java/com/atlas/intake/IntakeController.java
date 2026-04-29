package com.atlas.intake;

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
    public InterviewService.TurnResult turn(@RequestBody IntakeTurnRequest request) {
        return interviewService.next(request.state(), request.userInput());
    }

    @PostMapping("/remove")
    public RemovalService.RemovalResult remove(@RequestBody RemoveTurnRequest request) {
        return removalService.next(request.state(), request.userInput());
    }

    public record IntakeTurnRequest(InterviewState state, String userInput) {}

    public record RemoveTurnRequest(RemovalState state, String userInput) {}
}
