package com.atlas.intake;

import java.util.UUID;

/**
 * In-progress representation of one service-database link being captured.
 * If {@code databaseId} is null, the user is creating a brand-new database
 * entity; the {@code engine} field will be populated and a row inserted into
 * {@code databases} before the link. If {@code databaseId} is non-null, the
 * user is referencing an existing database — only the link is created.
 */
public record DatabaseUsageDraft(
        UUID databaseId,
        String databaseName,
        String engine,
        Boolean isOwner,
        String description) {

    public static DatabaseUsageDraft empty() {
        return new DatabaseUsageDraft(null, null, null, null, null);
    }

    public DatabaseUsageDraft withExistingDatabase(UUID id, String name) {
        return new DatabaseUsageDraft(id, name, engine, isOwner, description);
    }

    public DatabaseUsageDraft withNewDatabase(String name) {
        return new DatabaseUsageDraft(null, name, engine, isOwner, description);
    }

    public DatabaseUsageDraft withEngine(String v) {
        return new DatabaseUsageDraft(databaseId, databaseName, v, isOwner, description);
    }

    public DatabaseUsageDraft withIsOwner(boolean v) {
        return new DatabaseUsageDraft(databaseId, databaseName, engine, v, description);
    }

    public DatabaseUsageDraft withDescription(String v) {
        return new DatabaseUsageDraft(databaseId, databaseName, engine, isOwner, v);
    }

    public boolean isNewDatabase() {
        return databaseId == null;
    }
}
