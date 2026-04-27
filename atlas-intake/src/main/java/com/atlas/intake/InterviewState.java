package com.atlas.intake;

import java.util.LinkedHashSet;
import java.util.Set;

public record InterviewState(
        ServiceDraft draft,
        InterviewStage stage,
        boolean descriptionClarified,
        Set<InterviewStage> visitedOptionalStages,
        String lastError) {

    /**
     * Defensive default: deserialised payloads from older clients (or any
     * future field-omitting JSON) get an empty set rather than null, so
     * the walk logic can call {@link #hasVisitedOptional(InterviewStage)} freely.
     */
    public InterviewState {
        if (visitedOptionalStages == null) {
            visitedOptionalStages = Set.of();
        }
    }

    public static InterviewState empty() {
        return new InterviewState(ServiceDraft.empty(), null, false, Set.of(), null);
    }

    public InterviewState withDraft(ServiceDraft d) {
        return new InterviewState(d, stage, descriptionClarified, visitedOptionalStages, lastError);
    }

    public InterviewState atStage(InterviewStage s) {
        return new InterviewState(draft, s, descriptionClarified, visitedOptionalStages, lastError);
    }

    public InterviewState withDescriptionClarified() {
        return new InterviewState(draft, stage, true, visitedOptionalStages, lastError);
    }

    public InterviewState withError(String e) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages, e);
    }

    public InterviewState clearError() {
        return withError(null);
    }

    public InterviewState markOptionalVisited(InterviewStage s) {
        Set<InterviewStage> next = new LinkedHashSet<>(visitedOptionalStages);
        next.add(s);
        return new InterviewState(draft, stage, descriptionClarified, Set.copyOf(next), lastError);
    }

    public boolean hasVisitedOptional(InterviewStage s) {
        return visitedOptionalStages.contains(s);
    }
}
