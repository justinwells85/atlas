package com.atlas.codesync;

/**
 * Outcome counters for a single code-sync refresh. Tests assert on these
 * counts; the manual REST endpoint surfaces them in its response body.
 *
 * {@code skipped} counts endpoints present in the spec whose
 * {@code (method, path)} is already owned by an {@code 'intake'}-source row.
 * Code-sync defers to the human-curated row in that case rather than failing
 * the unique constraint; the row stays as {@code 'intake'} until the user
 * re-runs intake or the endpoint is removed from the human-curated set.
 */
public record CodeSyncResult(int created, int updated, int deleted, int skipped) {

    public static CodeSyncResult empty() {
        return new CodeSyncResult(0, 0, 0, 0);
    }
}
