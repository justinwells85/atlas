package com.atlas.intake;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final InterviewService interviewService;

    public IntakeController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @PostMapping("/turn")
    public InterviewService.TurnResult turn(@RequestBody IntakeTurnRequest request) {
        return interviewService.next(request.state(), request.userInput());
    }

    public record IntakeTurnRequest(InterviewState state, String userInput) {}
}
