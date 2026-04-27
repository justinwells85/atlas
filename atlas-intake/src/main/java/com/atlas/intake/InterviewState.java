package com.atlas.intake;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless-turn interview state. Carried in every {@code /api/intake/turn}
 * request body and echoed back in the response (per ADR-011). Holds the
 * services-row draft, optional-stage visit set, and per-section in-progress
 * lists / current item / closed flags for the M2 / M3 / 3.6 relationship
 * sections.
 *
 * Many fields, no abstraction: prototype-scope clarity wins over a generic
 * SectionState type that would complicate JSON round-tripping.
 */
public record InterviewState(
        ServiceDraft draft,
        InterviewStage stage,
        boolean descriptionClarified,
        Set<InterviewStage> visitedOptionalStages,

        // APIs section (M2 / 3.6 — the in-progress currentApi accumulates its
        // own consumers list inline; currentApiConsumer is the in-progress
        // consumer being built before being appended onto currentApi).
        List<ApiDraft> apis,
        ApiDraft currentApi,
        ApiConsumerDraft currentApiConsumer,
        boolean apisSectionClosed,

        // Upstream / downstream dependency sections (M2)
        List<DependencyEdgeDraft> upstreamDependencies,
        DependencyEdgeDraft currentUpstream,
        boolean upstreamSectionClosed,
        List<DependencyEdgeDraft> downstreamDependencies,
        DependencyEdgeDraft currentDownstream,
        boolean downstreamSectionClosed,

        // Databases section (M3)
        List<DatabaseUsageDraft> databaseUsages,
        DatabaseUsageDraft currentDatabaseUsage,
        boolean databasesSectionClosed,

        // External dependencies section (M3)
        List<ExternalDependencyUsageDraft> externalDependencyUsages,
        ExternalDependencyUsageDraft currentExternalDependencyUsage,
        boolean externalDependenciesSectionClosed,

        String lastError) {

    /** Defensive defaults so older client payloads don't NPE on collection access. */
    public InterviewState {
        if (visitedOptionalStages == null) visitedOptionalStages = Set.of();
        if (apis == null) apis = List.of();
        if (upstreamDependencies == null) upstreamDependencies = List.of();
        if (downstreamDependencies == null) downstreamDependencies = List.of();
        if (databaseUsages == null) databaseUsages = List.of();
        if (externalDependencyUsages == null) externalDependencyUsages = List.of();
    }

    public static InterviewState empty() {
        return new InterviewState(
                ServiceDraft.empty(), null, false, Set.of(),
                List.of(), null, null, false,
                List.of(), null, false,
                List.of(), null, false,
                List.of(), null, false,
                List.of(), null, false,
                null);
    }

    // --- Builders for individual fields ------------------------------------

    public InterviewState withDraft(ServiceDraft d) {
        return new InterviewState(d, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState atStage(InterviewStage s) {
        return new InterviewState(draft, s, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState withDescriptionClarified() {
        return new InterviewState(draft, stage, true, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState withError(String e) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                e);
    }

    public InterviewState clearError() {
        return withError(null);
    }

    public InterviewState markOptionalVisited(InterviewStage s) {
        Set<InterviewStage> next = new LinkedHashSet<>(visitedOptionalStages);
        next.add(s);
        return new InterviewState(draft, stage, descriptionClarified, Set.copyOf(next),
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public boolean hasVisitedOptional(InterviewStage s) {
        return visitedOptionalStages.contains(s);
    }

    // --- APIs section helpers ----------------------------------------------

    public InterviewState withCurrentApi(ApiDraft api) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, api, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState withCurrentApiConsumer(ApiConsumerDraft consumer) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, consumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    /**
     * Append the in-progress consumer onto the current API's consumers list
     * and clear currentApiConsumer. Caller must ensure currentApi and
     * currentApiConsumer are both non-null.
     */
    public InterviewState commitCurrentApiConsumer() {
        if (currentApi == null || currentApiConsumer == null) return this;
        ApiDraft updatedApi = currentApi.withAddedConsumer(currentApiConsumer);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, updatedApi, null, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentApi() {
        if (currentApi == null) return this;
        List<ApiDraft> nextApis = new ArrayList<>(apis);
        nextApis.add(currentApi);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                List.copyOf(nextApis), null, null, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState closeApisSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, null, null, true,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    // --- Upstream-deps section helpers -------------------------------------

    public InterviewState withCurrentUpstream(DependencyEdgeDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, d, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentUpstream() {
        if (currentUpstream == null) return this;
        List<DependencyEdgeDraft> next = new ArrayList<>(upstreamDependencies);
        next.add(currentUpstream);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                List.copyOf(next), null, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState closeUpstreamSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, null, true,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    // --- Downstream-deps section helpers -----------------------------------

    public InterviewState withCurrentDownstream(DependencyEdgeDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, d, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentDownstream() {
        if (currentDownstream == null) return this;
        List<DependencyEdgeDraft> next = new ArrayList<>(downstreamDependencies);
        next.add(currentDownstream);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                List.copyOf(next), null, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState closeDownstreamSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, null, true,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    // --- Databases section helpers (M3) ------------------------------------

    public InterviewState withCurrentDatabaseUsage(DatabaseUsageDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, d, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentDatabaseUsage() {
        if (currentDatabaseUsage == null) return this;
        List<DatabaseUsageDraft> next = new ArrayList<>(databaseUsages);
        next.add(currentDatabaseUsage);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                List.copyOf(next), null, databasesSectionClosed,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState closeDatabasesSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, null, true,
                externalDependencyUsages, currentExternalDependencyUsage, externalDependenciesSectionClosed,
                lastError);
    }

    // --- External-deps section helpers (M3) --------------------------------

    public InterviewState withCurrentExternalDependencyUsage(ExternalDependencyUsageDraft d) {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, d, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState commitCurrentExternalDependencyUsage() {
        if (currentExternalDependencyUsage == null) return this;
        List<ExternalDependencyUsageDraft> next = new ArrayList<>(externalDependencyUsages);
        next.add(currentExternalDependencyUsage);
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                List.copyOf(next), null, externalDependenciesSectionClosed,
                lastError);
    }

    public InterviewState closeExternalDependenciesSection() {
        return new InterviewState(draft, stage, descriptionClarified, visitedOptionalStages,
                apis, currentApi, currentApiConsumer, apisSectionClosed,
                upstreamDependencies, currentUpstream, upstreamSectionClosed,
                downstreamDependencies, currentDownstream, downstreamSectionClosed,
                databaseUsages, currentDatabaseUsage, databasesSectionClosed,
                externalDependencyUsages, null, true,
                lastError);
    }
}
