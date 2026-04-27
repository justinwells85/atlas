package com.atlas.intake;

import java.util.UUID;

/**
 * In-progress representation of one service-to-service dependency being
 * captured during the interview. The "other" side of the edge is the existing
 * service the user named; "this" side is the service being created (no UUID
 * until persistAndComplete). Stored with both name and id so the row write
 * has the id and any logging or later UX can show the name.
 */
public record DependencyEdgeDraft(UUID otherServiceId, String otherServiceName, String description) {

    public static DependencyEdgeDraft empty() {
        return new DependencyEdgeDraft(null, null, null);
    }

    public DependencyEdgeDraft withOther(UUID id, String name) {
        return new DependencyEdgeDraft(id, name, description);
    }

    public DependencyEdgeDraft withDescription(String v) {
        return new DependencyEdgeDraft(otherServiceId, otherServiceName, v);
    }
}
