package com.atlas.codesync;

import java.util.List;

/**
 * Output of parsing one pom.xml (M4 + Phase 5.6 M2). Holds the literal
 * declarations relevant to code-sync's metadata observations:
 *
 * <ul>
 *   <li>{@code groupId} / {@code artifactId} / {@code version} — this pom's coordinates.</li>
 *   <li>{@code parentGroupId} / {@code parentArtifactId} / {@code parentVersion} — the parent pom, if declared.</li>
 *   <li>{@code packaging} — pom packaging (e.g. {@code jar}, {@code pom}, {@code war}). Defaults to {@code jar} per Maven convention when not declared.</li>
 *   <li>{@code language} / {@code languageVersion} — inferred from pom shape ({@code Maven} → {@code Java}; {@code java.version} property when present).</li>
 *   <li>{@code framework} / {@code frameworkVersion} — inferred from the parent ({@code spring-boot-starter-parent} → {@code Spring Boot N.M.P}).</li>
 *   <li>{@code buildTool} — always {@code Maven} when this parser succeeds.</li>
 *   <li>{@code modules} — sub-module path strings declared in {@code <modules>}, in declaration order. Empty when this is a leaf pom. M2 of Phase 5.6 uses these to recurse the module tree.</li>
 *   <li>{@code dependencies} — declared {@code <dependency>} entries (literal coords; no resolution).</li>
 * </ul>
 *
 * Properties (e.g., {@code ${spring-boot.version}}) are NOT resolved —
 * the version may show up as the placeholder string. Parent inheritance
 * is NOT followed. The renderer surfaces what was declared, not what
 * Maven would compute.
 */
public record PomFacts(
        String groupId,
        String artifactId,
        String version,
        String parentGroupId,
        String parentArtifactId,
        String parentVersion,
        String packaging,
        String language,
        String languageVersion,
        String framework,
        String frameworkVersion,
        String buildTool,
        List<String> modules,
        List<DependencyCoords> dependencies) {
}
