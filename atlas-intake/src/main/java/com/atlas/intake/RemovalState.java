package com.atlas.intake;

import java.util.UUID;

/**
 * State carried in every {@code /api/intake/remove} request body and echoed
 * back in the response (mirrors the stateless-turn pattern used by the
 * registration interview, per ADR-011). Two stages: name lookup, then
 * yes/no confirmation showing the resolved service's name + owner team
 * so the caller can sanity-check before the soft-delete fires.
 */
public record RemovalState(
        RemovalStage stage,
        UUID candidateServiceId,
        String candidateServiceName,
        String candidateOwnerTeam,
        String lastError) {

    public static RemovalState empty() {
        return new RemovalState(null, null, null, null, null);
    }

    public RemovalState atStage(RemovalStage s) {
        return new RemovalState(s, candidateServiceId, candidateServiceName, candidateOwnerTeam, lastError);
    }

    public RemovalState withCandidate(UUID id, String name, String ownerTeam) {
        return new RemovalState(stage, id, name, ownerTeam, lastError);
    }

    public RemovalState withError(String e) {
        return new RemovalState(stage, candidateServiceId, candidateServiceName, candidateOwnerTeam, e);
    }

    public RemovalState clearError() {
        return withError(null);
    }
}
