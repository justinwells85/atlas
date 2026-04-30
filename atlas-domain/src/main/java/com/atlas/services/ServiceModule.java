package com.atlas.services;

import java.util.UUID;

/**
 * One Maven module observation belonging to a service (Phase 5.6 M2 — L4
 * drill-down). Append-only model: the "live view" returns the latest
 * observation per {@code (service_id, module_path, source)} where
 * {@code presence='present'}; tombstones live in the same table and are
 * filtered out by the live-view queries.
 *
 * <p>{@code modulePath} is relative to the service's
 * {@code services.module_path} root. The root module of a multi-module
 * service has {@code modulePath=""} (empty string; never null). Sub-modules
 * carry their relative path (e.g. {@code "billing-api"}, {@code "infra/db"}).
 *
 * <p>{@code declaredDeps} is a JSON-string list of {@code "groupId:artifactId"}
 * coords specific to THIS module — the renderer composes its own page from
 * this list rather than from the service-level union.
 */
public record ServiceModule(
        UUID id,
        UUID serviceId,
        String modulePath,
        String parentPath,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String languageVersion,
        String framework,
        String frameworkVersion,
        String declaredDeps,
        String source,
        String confluencePageId) {
}
