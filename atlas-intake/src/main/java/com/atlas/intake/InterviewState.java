package com.atlas.intake;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless-turn interview state. Carried in every {@code /api/intake/turn}
 * request body and echoed back in the response (per ADR-011). Holds the
 * services-row draft, optional-stage visit set, and per-section in-progress
 * lists / current item / closed flags for the M2 / M3 relationship sections.
 *
 * Many fields, no abstraction: prototype-scope clarity wins over a generic
 * SectionState type that would complicate JSON round-tripping.
 */
public record InterviewState(
        ServiceDraft draft,
        InterviewStage stage,
        boolean descriptionClarified,
        Set<InterviewStage> visitedOptionalStages,

        // APIs section (M2)
        List<ApiDraft> apis,
        ApiDraft currentApi,
        boolean apisSectionClosed,

        // Upstream / downstream dependency sections (M2)
        List<DependencyEdgeDraft> upstreamDependencies,
        DependencyEdgeDraft currentUpstream,
        boolean upstreamSectionClosed,
        List<DependencyEdgeDraft> downstreamDependencies,
        DependencyEdgeDraft currentDownstream,
        boolean downstreamSectionClosed,

        String lastError) {

    /** Defensive defaults so older client payloads don't NPE on collection access. */
    public InterviewState {
        if (visitedOptionalStages == null) visitedOptionalStages = Set.of();
        if (apis == null) apis = List.of();
        if (upstreamDependencies == null) upstreamDependencies = List.of();
        if (downstreamDependencies == null) downstreamDependencies = List.of();
    }

    public static InterviewState empty() {
        return new InterviewState(
                ServiceDraft.empty(), null, false, Set.of(),
                List.of(), null, false,
                List.of(), null, false,
                List.of(), null, false,
                null);
    }

    // --- Builders for individual fields ------------------------------------

    public InterviewState withDraft(ServiceDraft d) {
        return new InterviewState(d, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState atStage(InterviewStage s) {
        return new InterviewState(draft, s, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState withDescriptionClarified() {
        return new InterviewState(draft, stage, true, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState withError(String e) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                e);
    }

    public InterviewState clearError() {
        return withError(null);
    }

    public InterviewState markOptionalVisited(InterviewStage s) {
        Set<InterviewStage> next = new LinkedHashSet<>(visitedOptionalStages);
        next.add(s);
        return new InterviewState(draft, stage, descriptionClarified, Set.copyOf(next),
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public boolean hasVisitedOptional(InterviewStage s) {
        return visitedOptionalStages.contains(s);
    }

    // --- APIs section helpers ----------------------------------------------

    public InterviewState withCurrentApi(ApiDraft api) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, api, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentApi() {
        if (currentApi == null) return this;
        List<ApiDraft> nextApis = new ArrayList<>(apis);
        nextApis.add(currentApi);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                List.copyOf(nextApis), null, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState closeApisSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, null, true,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    // --- Upstream-deps section helpers -------------------------------------

    public InterviewState withCurrentUpstream(DependencyEdgeDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, d, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentUpstream() {
        if (currentUpstream == null) return this;
        List<DependencyEdgeDraft> next = new ArrayList<>(upstreamDependencies);
        next.add(currentUpstream);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                List.copyOf(next), null, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    public InterviewState closeUpstreamSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, null, true,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                lastError);
    }

    // --- Downstream-deps section helpers -----------------------------------

    public InterviewState withCurrentDownstream(DependencyEdgeDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, d, downstreamSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentDownstream() {
        if (currentDownstream == null) return this;
        List<DependencyEdgeDraft> next = new ArrayList<>(downstreamDependencies);
        next.add(currentDownstream);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                List.copyOf(next), null, downstreamSectionClosed,
                lastError);
    }

    public InterviewState closeDownstreamSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, null, true,
                lastError);
    }
}
