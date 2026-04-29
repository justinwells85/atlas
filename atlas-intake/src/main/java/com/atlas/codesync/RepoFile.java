package com.atlas.codesync;

/**
 * One Java source file fetched from a remote repo.
 * {@code path} is the repository-relative path (e.g.,
 * {@code "atlas-intake/src/test/java/com/atlas/CodeSyncCoordinatorTest.java"}).
 */
public record RepoFile(String path, String content) {
}
