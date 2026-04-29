package com.atlas.codesync;

/**
 * One declared dependency from a pom.xml: literal groupId / artifactId /
 * version. Maven's full coordinate set has more (classifier, type, scope);
 * those are deliberately not surfaced — code-sync's external-dep
 * observations need only the identity triple. {@code version} may be null
 * when inherited from a BOM; {@code groupId} may also be null when
 * inherited via dependency management.
 */
public record DependencyCoords(String groupId, String artifactId, String version) {
}
