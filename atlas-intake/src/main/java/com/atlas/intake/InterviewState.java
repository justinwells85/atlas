package com.atlas.intake;

public record InterviewState(
        ServiceDraft draft,
        InterviewStage stage,
        boolean descriptionClarified,
        String lastError) {

    public static InterviewState empty() {
        return new InterviewState(ServiceDraft.empty(), null, false, null);
    }

    public InterviewState withDraft(ServiceDraft d) {
        return new InterviewState(d, stage, descriptionClarified, lastError);
    }

    public InterviewState atStage(InterviewStage s) {
        return new InterviewState(draft, s, descriptionClarified, lastError);
    }

    public InterviewState withDescriptionClarified() {
        return new InterviewState(draft, stage, true, lastError);
    }

    public InterviewState withError(String e) {
        return new InterviewState(draft, stage, descriptionClarified, e);
    }

    public InterviewState clearError() {
        return withError(null);
    }
}
