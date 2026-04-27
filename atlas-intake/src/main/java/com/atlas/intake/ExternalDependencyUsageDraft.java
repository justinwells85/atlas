package com.atlas.intake;

import java.util.UUID;

/**
 * In-progress representation of one service-external-dependency link being
 * captured. If {@code externalDependencyId} is null, the user is creating a
 * new external dependency entity; the {@code url} field may be populated and
 * a row inserted into {@code external_dependencies} before the link. If
 * non-null, the user is referencing an existing entity — only the link is
 * created.
 */
public record ExternalDependencyUsageDraft(
        UUID externalDependencyId,
        String name,
        String url,
        String description) {

    public static ExternalDependencyUsageDraft empty() {
        return new ExternalDependencyUsageDraft(null, null, null, null);
    }

    public ExternalDependencyUsageDraft withExistingDependency(UUID id, String name) {
        return new ExternalDependencyUsageDraft(id, name, url, description);
    }

    public ExternalDependencyUsageDraft withNewDependency(String name) {
        return new ExternalDependencyUsageDraft(null, name, url, description);
    }

    public ExternalDependencyUsageDraft withUrl(String v) {
        return new ExternalDependencyUsageDraft(externalDependencyId, name, v, description);
    }

    public ExternalDependencyUsageDraft withDescription(String v) {
        return new ExternalDependencyUsageDraft(externalDependencyId, name, url, v);
    }

    public boolean isNewDependency() {
        return externalDependencyId == null;
    }
}
