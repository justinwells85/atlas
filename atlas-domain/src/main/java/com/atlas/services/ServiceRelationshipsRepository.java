package com.atlas.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to a service's relationship rows: APIs it exposes, databases it uses,
 * third-party dependencies, and upstream/downstream service-to-service edges.
 * JdbcTemplate (not JPA) so the joins stay obvious and the read shape is
 * independent of any future entity mappings. Phase 3.5 added the write methods
 * that the intake interview calls when persisting captured relationship data.
 */
@Repository
public class ServiceRelationshipsRepository {

    private final JdbcTemplate jdbc;

    public ServiceRelationshipsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Writes (Phase 3.5) -------------------------------------------------

    /**
     * Insert one observation in {@code apis} (M3.5: append-only ingestion).
     * {@code source} must be one of {@code intake}, {@code openapi},
     * {@code pom-xml}, {@code tests} (CHECK-constrained at the DB layer per V13).
     * {@code presence} is {@code 'present'} for normal observations or
     * {@code 'absent'} for tombstones recording disappearance.
     * {@code observed_at} defaults to now.
     */
    public UUID insertApi(UUID serviceId, String path, String method,
                          String authMethod, String description, String source,
                          String presence, String confluencePageId,
                          String openapiSnapshot) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO apis (id, service_id, path, method, auth_method, description, source, presence, confluence_page_id, openapi_snapshot) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, path, method, authMethod, description, source, presence,
                confluencePageId, openapiSnapshot);
        return id;
    }

    /** Backwards-compatible overload — pre-V21 callers don't carry a snapshot. */
    public UUID insertApi(UUID serviceId, String path, String method,
                          String authMethod, String description, String source,
                          String presence, String confluencePageId) {
        return insertApi(serviceId, path, method, authMethod, description, source,
                presence, confluencePageId, null);
    }

    /**
     * Convenience overload for the common "live observation, no page id yet" case.
     * Equivalent to {@link #insertApi(UUID, String, String, String, String, String, String, String, String)}
     * with {@code presence='present'} and {@code confluencePageId=null}.
     */
    public UUID insertApi(UUID serviceId, String path, String method,
                          String authMethod, String description, String source) {
        return insertApi(serviceId, path, method, authMethod, description, source,
                "present", null, null);
    }

    /**
     * Append a tombstone observation recording that a {@code (method, path, source)}
     * triple is no longer present in its upstream artifact (M3.5). The
     * {@code confluencePageId} is carried forward from the previous live row
     * so the sync coordinator's cleanup pass can find and delete the page,
     * then null the field on the tombstone (mutable bookkeeping).
     */
    public UUID writeApiTombstone(UUID serviceId, String method, String path,
                                  String source, String confluencePageId) {
        return insertApi(serviceId, path, method, null, null, source, "absent", confluencePageId);
    }

    /**
     * Live view: latest observation per {@code (service_id, method, path, source)}
     * for the service, filtered to {@code presence='present'}. Used by the
     * renderer and MCP. Tombstones and superseded observations are excluded.
     */
    public List<ApiSummary> findApisBySource(UUID serviceId, String source) {
        return jdbc.query(
                liveApisSql() + " AND a.service_id = ? AND a.source = ? " +
                        "ORDER BY a.path, a.method",
                (rs, i) -> mapApi(rs),
                serviceId, source);
    }

    /** Stamp the Confluence page id on one api row after the sync coordinator creates its endpoint page. */
    public void setApiConfluencePageId(UUID apiId, String confluencePageId) {
        jdbc.update(
                "UPDATE apis SET confluence_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                confluencePageId, apiId);
    }

    /**
     * Tombstone rows whose wiki pages still need to be removed: latest
     * observation per key is {@code presence='absent'} and carries a non-null
     * ref on at least one sink ({@code confluence_page_id} OR
     * {@code local_markdown_path}). Bypasses the live-only filter. Only
     * includes tombstones whose owning service is itself still live
     * (soft-deleted services are handled by {@code cleanupDeletedServices}).
     */
    public List<SoftDeletedApiPage> findStaleApiPages() {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, method, path, source, presence, confluence_page_id, local_markdown_path, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, method, path, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM apis " +
                        ") " +
                        "SELECT l.id, l.method, l.path, l.confluence_page_id, l.local_markdown_path, " +
                        "       s.id AS service_id, s.name AS service_name " +
                        "FROM latest l " +
                        "JOIN services s ON s.id = l.service_id " +
                        "WHERE l.rn = 1 AND l.presence = 'absent' " +
                        "  AND (l.confluence_page_id IS NOT NULL OR l.local_markdown_path IS NOT NULL) " +
                        "  AND s.deleted_at IS NULL " +
                        "ORDER BY s.name, l.method, l.path",
                (rs, i) -> new SoftDeletedApiPage(
                        (UUID) rs.getObject("id"),
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getString("confluence_page_id"),
                        rs.getString("local_markdown_path"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("service_name")));
    }

    /**
     * Live view: intake-source api observations on the given service whose
     * {@code (method, path)} key has no live openapi-source counterpart. Used
     * by the M4.5 stale-intake cleanup path to identify intake-recorded
     * endpoints that the code no longer exposes (e.g., pre-rename rows that
     * the human interview never updated). Tombstoned intake observations are
     * excluded — only currently-live intake rows that nothing in code-sync
     * confirms.
     */
    public List<ApiSummary> findStaleIntakeApis(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, path, method, auth_method, description, source, " +
                        "         presence, confluence_page_id, local_markdown_path, openapi_snapshot, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, method, path, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM apis " +
                        ") " +
                        "SELECT i.id, i.path, i.method, i.auth_method, i.description, i.source, " +
                        "       i.confluence_page_id, i.openapi_snapshot, i.local_markdown_path " +
                        "FROM latest i " +
                        "WHERE i.rn = 1 AND i.presence = 'present' " +
                        "  AND i.source = 'intake' AND i.service_id = ? " +
                        "  AND NOT EXISTS (" +
                        "    SELECT 1 FROM latest o " +
                        "    WHERE o.rn = 1 AND o.presence = 'present' AND o.source = 'openapi' " +
                        "      AND o.service_id = i.service_id " +
                        "      AND o.method = i.method AND o.path = i.path" +
                        "  ) " +
                        "ORDER BY i.path, i.method",
                (rs, i) -> mapApi(rs),
                serviceId);
    }

    /** Null an api row's confluence_page_id. Used by the cleanup pass after the page is gone in Confluence. */
    public void clearApiConfluencePageId(UUID apiId) {
        jdbc.update("UPDATE apis SET confluence_page_id = NULL WHERE id = ?", apiId);
    }

    /** Stamp the local-markdown vault path on one api observation row. M3 of Phase 5.8. */
    public void setApiLocalMarkdownPath(UUID apiId, String localMarkdownPath) {
        jdbc.update("UPDATE apis SET local_markdown_path = ? WHERE id = ?",
                localMarkdownPath, apiId);
    }

    /** Null an api row's local_markdown_path after the file is deleted. */
    public void clearApiLocalMarkdownPath(UUID apiId) {
        jdbc.update("UPDATE apis SET local_markdown_path = NULL WHERE id = ?", apiId);
    }

    /**
     * SELECT clause + base FROM/WHERE for the apis live-view: latest
     * observation per {@code (service_id, method, path, source)} where
     * {@code presence='present'}. Append additional filters with {@code AND ...}
     * and an {@code ORDER BY} clause as needed.
     */
    private static String liveApisSql() {
        return "WITH latest AS (" +
                "  SELECT id, service_id, path, method, auth_method, description, source, " +
                "         presence, confluence_page_id, local_markdown_path, openapi_snapshot, " +
                "         ROW_NUMBER() OVER (PARTITION BY service_id, method, path, source " +
                "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                "  FROM apis " +
                ") " +
                "SELECT id, path, method, auth_method, description, source, confluence_page_id, " +
                "       openapi_snapshot, local_markdown_path " +
                "FROM latest a " +
                "WHERE a.rn = 1 AND a.presence = 'present'";
    }

    private static ApiSummary mapApi(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiSummary(
                (UUID) rs.getObject("id"),
                rs.getString("path"),
                rs.getString("method"),
                rs.getString("auth_method"),
                rs.getString("description"),
                rs.getString("source"),
                rs.getString("confluence_page_id"),
                rs.getString("openapi_snapshot"),
                rs.getString("local_markdown_path"));
    }

    // --- service_test_scenarios (M3, append-only as of M3.5) -------------

    /**
     * Live view: latest observation per
     * {@code (service_id, package_name, class_name, method_name)} where
     * {@code presence='present'}. Used by the renderer.
     */
    public List<TestScenario> findTestScenariosFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, package_name, class_name, method_name, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, package_name, class_name, method_name " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_test_scenarios " +
                        ") " +
                        "SELECT id, service_id, package_name, class_name, method_name, source " +
                        "FROM latest WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY package_name, class_name, method_name",
                (rs, i) -> new TestScenario(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("package_name"),
                        rs.getString("class_name"),
                        rs.getString("method_name"),
                        rs.getString("source")),
                serviceId);
    }

    /**
     * Append one test-scenario observation. Defaults to {@code presence='present'}.
     * Append a tombstone via {@link #writeTestScenarioTombstone}.
     */
    public UUID insertTestScenario(UUID serviceId, String packageName,
                                   String className, String methodName, String source) {
        return insertTestScenario(serviceId, packageName, className, methodName, source, "present");
    }

    public UUID insertTestScenario(UUID serviceId, String packageName,
                                   String className, String methodName, String source,
                                   String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_test_scenarios " +
                        "(id, service_id, package_name, class_name, method_name, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, packageName == null ? "" : packageName,
                className, methodName, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for a removed test method. */
    public UUID writeTestScenarioTombstone(UUID serviceId, String packageName,
                                           String className, String methodName, String source) {
        return insertTestScenario(serviceId, packageName, className, methodName, source, "absent");
    }

    // --- service_metadata (M4, append-only from day one) ----------------

    /**
     * Live view: latest observation per {@code (service_id, metadata_key, source)}
     * where {@code presence='present'}. Multiple sources can hold parallel
     * truths for the same key (e.g., intake says "Java" and pom-xml also says
     * "Java"); callers pick a precedence as needed.
     */
    public List<ServiceMetadata> findServiceMetadataFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, metadata_key, metadata_value, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, metadata_key, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_metadata " +
                        ") " +
                        "SELECT id, service_id, metadata_key, metadata_value, source " +
                        "FROM latest WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY metadata_key, source",
                (rs, i) -> new ServiceMetadata(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("metadata_key"),
                        rs.getString("metadata_value"),
                        rs.getString("source")),
                serviceId);
    }

    /** Append one metadata observation. Defaults to {@code presence='present'}. */
    public UUID insertServiceMetadata(UUID serviceId, String key, String value, String source) {
        return insertServiceMetadata(serviceId, key, value, source, "present");
    }

    public UUID insertServiceMetadata(UUID serviceId, String key, String value,
                                      String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_metadata " +
                        "(id, service_id, metadata_key, metadata_value, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                id, serviceId, key, value, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} metadata tombstone. */
    public UUID writeServiceMetadataTombstone(UUID serviceId, String key, String source) {
        return insertServiceMetadata(serviceId, key, null, source, "absent");
    }

    // --- service_external_deps (append-only as of M4 / V19) -------------

    /**
     * Append one service_external_deps observation. The ON DELETE CASCADE on
     * external_dependency_id is intact; if you {@link #insertExternalDependency}
     * separately, link the resulting id here.
     */
    public UUID insertServiceExternalDepObservation(UUID serviceId, UUID externalDependencyId,
                                                    String description, String source) {
        return insertServiceExternalDepObservation(serviceId, externalDependencyId, description,
                source, "present");
    }

    public UUID insertServiceExternalDepObservation(UUID serviceId, UUID externalDependencyId,
                                                    String description, String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_external_deps " +
                        "(id, service_id, external_dependency_id, description, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                id, serviceId, externalDependencyId, description, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for an external dep no longer declared. */
    public UUID writeServiceExternalDepTombstone(UUID serviceId, UUID externalDependencyId, String source) {
        return insertServiceExternalDepObservation(serviceId, externalDependencyId, null, source, "absent");
    }

    /**
     * Live view of {@code external_dependency_id}s a service currently
     * has under a given {@code source} (latest observation per
     * {@code (service_id, external_dependency_id, source)} where
     * {@code presence='present'}). Used by code-sync's append-only diff.
     */
    public List<UUID> findLiveExternalDepsForService(UUID serviceId, String source) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, external_dependency_id, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, external_dependency_id, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_external_deps " +
                        ") " +
                        "SELECT external_dependency_id FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? AND source = ?",
                (rs, i) -> (UUID) rs.getObject("external_dependency_id"),
                serviceId, source);
    }

    /** Update services.tests_page_id after the sync coordinator creates the per-service tests page. */
    public void setServiceTestsPageId(UUID serviceId, String pageId) {
        jdbc.update(
                "UPDATE services SET tests_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                pageId, serviceId);
    }

    /** Update services.beans_page_id after the sync coordinator creates the per-service Beans page (Phase 5.6 M3). */
    public void setServiceBeansPageId(UUID serviceId, String pageId) {
        jdbc.update(
                "UPDATE services SET beans_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                pageId, serviceId);
    }

    /** Update services.tests_markdown_path after the Markdown sink writes the tests page. M3 of Phase 5.8. */
    public void setServiceTestsMarkdownPath(UUID serviceId, String path) {
        jdbc.update(
                "UPDATE services SET tests_markdown_path = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                path, serviceId);
    }

    /** Update services.beans_markdown_path after the Markdown sink writes the beans page. M3 of Phase 5.8. */
    public void setServiceBeansMarkdownPath(UUID serviceId, String path) {
        jdbc.update(
                "UPDATE services SET beans_markdown_path = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                path, serviceId);
    }

    /** Update services.configuration_page_id after the sync coordinator creates the per-service Configuration page (Phase 5.9 M4). */
    public void setServiceConfigurationPageId(UUID serviceId, String pageId) {
        jdbc.update(
                "UPDATE services SET configuration_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                pageId, serviceId);
    }

    /** Update services.configuration_markdown_path after the Markdown sink writes the configuration page (Phase 5.9 M4). */
    public void setServiceConfigurationMarkdownPath(UUID serviceId, String path) {
        jdbc.update(
                "UPDATE services SET configuration_markdown_path = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                path, serviceId);
    }

    // --- service_beans (Phase 5.6 M3 — append-only from day one) -------

    /**
     * Append one bean observation. Defaults to {@code presence='present'}
     * with source {@code 'source-tree'} (the AST-extraction writer).
     * {@code modulePath} must be non-null — pass {@code ""} for root-rooted
     * services.
     */
    public UUID insertBean(UUID serviceId, String modulePath, String packageName,
                           String className, String stereotype,
                           String classJavadocSummary, String publicMethods) {
        return insertBean(serviceId, modulePath, packageName, className, stereotype,
                classJavadocSummary, publicMethods, "source-tree", "present");
    }

    public UUID insertBean(UUID serviceId, String modulePath, String packageName,
                           String className, String stereotype,
                           String classJavadocSummary, String publicMethods,
                           String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_beans " +
                        "(id, service_id, module_path, package_name, class_name, stereotype, " +
                        " class_javadoc_summary, public_methods, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, modulePath == null ? "" : modulePath,
                packageName == null ? "" : packageName,
                className, stereotype, classJavadocSummary, publicMethods,
                source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for a bean class no longer in the source tree. */
    public UUID writeBeanTombstone(UUID serviceId, String modulePath, String packageName,
                                   String className, String source) {
        return insertBean(serviceId, modulePath, packageName, className,
                // Stereotype is required by the table CHECK; carry forward
                // a sensible default for tombstones — the renderer never
                // surfaces tombstones, so the value is bookkeeping-only.
                "Component",
                null, null, source, "absent");
    }

    /**
     * Live view: latest observation per
     * {@code (service_id, module_path, package_name, class_name, source)}
     * where {@code presence='present'}. Used by the Beans page renderer.
     */
    public List<ServiceBean> findBeansFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, module_path, package_name, class_name, stereotype, " +
                        "         class_javadoc_summary, public_methods, source, presence, " +
                        "         confluence_page_id, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, package_name, class_name, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_beans " +
                        ") " +
                        "SELECT id, service_id, module_path, package_name, class_name, stereotype, " +
                        "       class_javadoc_summary, public_methods, source, confluence_page_id " +
                        "FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY stereotype, package_name, class_name",
                (rs, i) -> new ServiceBean(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("module_path"),
                        rs.getString("package_name"),
                        rs.getString("class_name"),
                        rs.getString("stereotype"),
                        rs.getString("class_javadoc_summary"),
                        rs.getString("public_methods"),
                        rs.getString("source"),
                        rs.getString("confluence_page_id")),
                serviceId);
    }

    // --- service_modules (Phase 5.6 M2 — append-only from day one) -----

    /**
     * Append one module observation. Defaults to {@code presence='present'}
     * with no Confluence page id yet. {@code modulePath} must be non-null
     * — pass {@code ""} for the root module of the service.
     */
    public UUID insertModule(UUID serviceId, String modulePath, String parentPath,
                             String groupId, String artifactId, String version,
                             String packaging, String languageVersion, String framework,
                             String frameworkVersion, String declaredDeps, String source) {
        return insertModule(serviceId, modulePath, parentPath, groupId, artifactId, version,
                packaging, languageVersion, framework, frameworkVersion, declaredDeps,
                source, "present", null);
    }

    public UUID insertModule(UUID serviceId, String modulePath, String parentPath,
                             String groupId, String artifactId, String version,
                             String packaging, String languageVersion, String framework,
                             String frameworkVersion, String declaredDeps, String source,
                             String presence, String confluencePageId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_modules " +
                        "(id, service_id, module_path, parent_path, group_id, artifact_id, version, " +
                        " packaging, language_version, framework, framework_version, declared_deps, " +
                        " source, presence, confluence_page_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, modulePath, parentPath, groupId, artifactId, version,
                packaging, languageVersion, framework, frameworkVersion, declaredDeps,
                source, presence, confluencePageId);
        return id;
    }

    /**
     * Append a {@code presence='absent'} tombstone for a module that's no
     * longer present in the upstream pom tree. Carries forward the previous
     * {@code confluence_page_id} so the cleanup pass can delete the orphan
     * Confluence page.
     */
    public UUID writeModuleTombstone(UUID serviceId, String modulePath, String source,
                                     String confluencePageId) {
        return insertModule(serviceId, modulePath, null, null, null, null, null, null,
                null, null, null, source, "absent", confluencePageId);
    }

    /**
     * Live view: latest observation per {@code (service_id, module_path, source)}
     * for the service, filtered to {@code presence='present'}. Tombstones
     * and superseded observations are excluded.
     */
    public List<ServiceModule> findModulesFor(UUID serviceId) {
        return jdbc.query(
                liveModulesSql() + " AND service_id = ? ORDER BY module_path",
                (rs, i) -> mapModule(rs),
                serviceId);
    }

    /** Stamp the Confluence page id on one module observation row after the page is created. */
    public void setModuleConfluencePageId(UUID moduleObservationId, String confluencePageId) {
        jdbc.update(
                "UPDATE service_modules SET confluence_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                confluencePageId, moduleObservationId);
    }

    /** Null a module observation row's confluence_page_id. Used by the cleanup pass after the page is gone. */
    public void clearModuleConfluencePageId(UUID moduleObservationId) {
        jdbc.update("UPDATE service_modules SET confluence_page_id = NULL WHERE id = ?",
                moduleObservationId);
    }

    /** Stamp the local-markdown vault path on one module observation row. M3 of Phase 5.8. */
    public void setModuleLocalMarkdownPath(UUID moduleObservationId, String localMarkdownPath) {
        jdbc.update("UPDATE service_modules SET local_markdown_path = ? WHERE id = ?",
                localMarkdownPath, moduleObservationId);
    }

    /** Null a module observation row's local_markdown_path. */
    public void clearModuleLocalMarkdownPath(UUID moduleObservationId) {
        jdbc.update("UPDATE service_modules SET local_markdown_path = NULL WHERE id = ?",
                moduleObservationId);
    }

    /**
     * Tombstoned module rows whose Confluence pages still need removing:
     * latest observation per key is {@code presence='absent'} and carries a
     * non-null {@code confluence_page_id}. Bypasses the live-only filter.
     * Mirrors {@link #findStaleApiPages()} at the module grain.
     */
    public List<SoftDeletedModulePage> findStaleModulePages() {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, module_path, source, presence, confluence_page_id, local_markdown_path, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_modules " +
                        ") " +
                        "SELECT l.id, l.module_path, l.confluence_page_id, l.local_markdown_path, " +
                        "       s.id AS service_id, s.name AS service_name " +
                        "FROM latest l " +
                        "JOIN services s ON s.id = l.service_id " +
                        "WHERE l.rn = 1 AND l.presence = 'absent' " +
                        "  AND (l.confluence_page_id IS NOT NULL OR l.local_markdown_path IS NOT NULL) " +
                        "  AND s.deleted_at IS NULL " +
                        "ORDER BY s.name, l.module_path",
                (rs, i) -> new SoftDeletedModulePage(
                        (UUID) rs.getObject("id"),
                        rs.getString("module_path"),
                        rs.getString("confluence_page_id"),
                        rs.getString("local_markdown_path"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("service_name")));
    }

    private static String liveModulesSql() {
        return "WITH latest AS (" +
                "  SELECT id, service_id, module_path, parent_path, group_id, artifact_id, version, " +
                "         packaging, language_version, framework, framework_version, declared_deps, " +
                "         source, presence, confluence_page_id, local_markdown_path, " +
                "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, source " +
                "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                "  FROM service_modules " +
                ") " +
                "SELECT id, service_id, module_path, parent_path, group_id, artifact_id, version, " +
                "       packaging, language_version, framework, framework_version, declared_deps, " +
                "       source, confluence_page_id, local_markdown_path " +
                "FROM latest " +
                "WHERE rn = 1 AND presence = 'present'";
    }

    private static ServiceModule mapModule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ServiceModule(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("service_id"),
                rs.getString("module_path"),
                rs.getString("parent_path"),
                rs.getString("group_id"),
                rs.getString("artifact_id"),
                rs.getString("version"),
                rs.getString("packaging"),
                rs.getString("language_version"),
                rs.getString("framework"),
                rs.getString("framework_version"),
                rs.getString("declared_deps"),
                rs.getString("source"),
                rs.getString("confluence_page_id"),
                rs.getString("local_markdown_path"));
    }

    /** Insert one row in {@code api_consumers} linking an API to a consumer service. */
    public void insertApiConsumer(UUID apiId, UUID consumerServiceId, String description) {
        jdbc.update(
                "INSERT INTO api_consumers (id, api_id, consumer_service_id, description) " +
                        "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), apiId, consumerServiceId, description);
    }

    /**
     * Insert one row in {@code service_changes} (audit). {@code changedBy} is a
     * static label for now ("intake-agent", "mcp-update_service") — switches to
     * the authenticated principal when auth lands. {@code before} and
     * {@code after} JSON snapshots are left null for the prototype; adding them
     * is a follow-up if/when richer change views are needed.
     */
    public void insertServiceChange(UUID serviceId, String changedBy,
                                    String changeType, String summary) {
        jdbc.update(
                "INSERT INTO service_changes (id, service_id, changed_by, change_type, summary) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, changedBy, changeType, summary);
    }

    /** Insert one directed edge in {@code service_dependencies}. */
    public void insertServiceDependency(UUID upstreamServiceId, UUID downstreamServiceId,
                                        String description) {
        jdbc.update(
                "INSERT INTO service_dependencies " +
                        "(id, upstream_service_id, downstream_service_id, description) " +
                        "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), upstreamServiceId, downstreamServiceId, description);
    }

    /** Lookup an existing database by name. */
    public Optional<UUID> findDatabaseIdByName(String name) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM data_stores WHERE name = ?",
                (rs, i) -> (UUID) rs.getObject("id"),
                name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Insert one row in {@code data_stores} (the schema's name for the databases entity). Returns the generated id. */
    public UUID insertDatabase(String name, String engine) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO data_stores (id, name, engine) VALUES (?, ?, ?)",
                id, name, engine);
        return id;
    }

    /** Insert one row linking a service to a database (with ownership flag). */
    public void insertServiceDatabaseLink(UUID serviceId, UUID databaseId,
                                          boolean isOwner, String description) {
        jdbc.update(
                "INSERT INTO service_databases " +
                        "(id, service_id, database_id, is_owner, description) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, databaseId, isOwner, description);
    }

    /** Lookup an existing external dependency by name. */
    public Optional<UUID> findExternalDependencyIdByName(String name) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM external_dependencies WHERE name = ?",
                (rs, i) -> (UUID) rs.getObject("id"),
                name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Insert one row in {@code external_dependencies}. Returns the generated id. */
    public UUID insertExternalDependency(String name, String url) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO external_dependencies (id, name, url) VALUES (?, ?, ?)",
                id, name, url);
        return id;
    }

    /** Insert one row linking a service to an external dependency. */
    public void insertServiceExternalDepLink(UUID serviceId, UUID externalDependencyId,
                                             String description) {
        jdbc.update(
                "INSERT INTO service_external_deps " +
                        "(id, service_id, external_dependency_id, description) " +
                        "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, externalDependencyId, description);
    }

    // --- Reads (Phase 3) ----------------------------------------------------

    public List<ApiSummary> findApisFor(UUID serviceId) {
        return jdbc.query(
                liveApisSql() + " AND a.service_id = ? ORDER BY a.path, a.method",
                (rs, i) -> mapApi(rs),
                serviceId);
    }

    /** Services that {@code serviceId} depends on. */
    public List<ServiceDependencyEdge> findUpstreamDependenciesOf(UUID serviceId) {
        return jdbc.query(
                "SELECT sd.upstream_service_id, us.name AS upstream_name, " +
                        "       sd.downstream_service_id, ds.name AS downstream_name, " +
                        "       sd.description " +
                        "FROM service_dependencies sd " +
                        "JOIN services us ON sd.upstream_service_id = us.id " +
                        "JOIN services ds ON sd.downstream_service_id = ds.id " +
                        "WHERE sd.downstream_service_id = ? " +
                        "ORDER BY us.name",
                this::mapDependencyEdge,
                serviceId);
    }

    /**
     * All service-to-service dependency edges in the graph, with both
     * endpoints filtered to non-soft-deleted services. Used by the
     * architecture-map renderer.
     */
    public List<ServiceDependencyEdge> findAllServiceDependencies() {
        return jdbc.query(
                "SELECT sd.upstream_service_id, us.name AS upstream_name, " +
                        "       sd.downstream_service_id, ds.name AS downstream_name, " +
                        "       sd.description " +
                        "FROM service_dependencies sd " +
                        "JOIN services us ON sd.upstream_service_id = us.id " +
                        "JOIN services ds ON sd.downstream_service_id = ds.id " +
                        "WHERE us.deleted_at IS NULL AND ds.deleted_at IS NULL " +
                        "ORDER BY us.name, ds.name",
                this::mapDependencyEdge);
    }

    /** Services that depend on {@code serviceId}. */
    public List<ServiceDependencyEdge> findDownstreamDependenciesOf(UUID serviceId) {
        return jdbc.query(
                "SELECT sd.upstream_service_id, us.name AS upstream_name, " +
                        "       sd.downstream_service_id, ds.name AS downstream_name, " +
                        "       sd.description " +
                        "FROM service_dependencies sd " +
                        "JOIN services us ON sd.upstream_service_id = us.id " +
                        "JOIN services ds ON sd.downstream_service_id = ds.id " +
                        "WHERE sd.upstream_service_id = ? " +
                        "ORDER BY ds.name",
                this::mapDependencyEdge,
                serviceId);
    }

    public List<DatabaseUsage> findDatabasesFor(UUID serviceId) {
        return jdbc.query(
                "SELECT db.id, db.name, db.engine, sdb.is_owner, sdb.description " +
                        "FROM service_databases sdb " +
                        "JOIN data_stores db ON sdb.database_id = db.id " +
                        "WHERE sdb.service_id = ? " +
                        "ORDER BY db.name",
                (rs, i) -> new DatabaseUsage(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("engine"),
                        rs.getBoolean("is_owner"),
                        rs.getString("description")),
                serviceId);
    }

    /**
     * Inventory view: every data store with one row per using service (or one
     * row with null service fields if the store has no users). Used to render
     * the data-store inventory page.
     */
    public List<DataStoreInventoryRow> findAllDataStoresWithUsages() {
        return jdbc.query(
                "SELECT ds.id AS ds_id, ds.name AS ds_name, ds.engine AS ds_engine, " +
                        "       s.id AS svc_id, s.name AS svc_name, sdb.is_owner, sdb.description " +
                        "FROM data_stores ds " +
                        "LEFT JOIN service_databases sdb ON sdb.database_id = ds.id " +
                        "LEFT JOIN services s ON s.id = sdb.service_id " +
                        "ORDER BY ds.name, s.name",
                (rs, i) -> new DataStoreInventoryRow(
                        (UUID) rs.getObject("ds_id"),
                        rs.getString("ds_name"),
                        rs.getString("ds_engine"),
                        (UUID) rs.getObject("svc_id"),
                        rs.getString("svc_name"),
                        rs.getObject("is_owner") == null ? null : rs.getBoolean("is_owner"),
                        rs.getString("description")));
    }

    /**
     * Inventory view: every external dependency with one row per using service
     * (or one row with null service fields if the dep has no users). Used to
     * render the external-deps inventory page.
     */
    public List<ExternalDependencyInventoryRow> findAllExternalDependenciesWithUsages() {
        return jdbc.query(
                "SELECT ed.id AS ed_id, ed.name AS ed_name, ed.url AS ed_url, " +
                        "       s.id AS svc_id, s.name AS svc_name, sed.description " +
                        "FROM external_dependencies ed " +
                        "LEFT JOIN service_external_deps sed ON sed.external_dependency_id = ed.id " +
                        "LEFT JOIN services s ON s.id = sed.service_id " +
                        "ORDER BY ed.name, s.name",
                (rs, i) -> new ExternalDependencyInventoryRow(
                        (UUID) rs.getObject("ed_id"),
                        rs.getString("ed_name"),
                        rs.getString("ed_url"),
                        (UUID) rs.getObject("svc_id"),
                        rs.getString("svc_name"),
                        rs.getString("description")));
    }

    /**
     * Clear every relationship row attached to a service: APIs the service
     * exposes, edges where it's upstream or downstream, databases it uses,
     * external dependencies it consumes, and any rows where it consumes
     * another service's API. Used by intake's reactivate path (ADR-014) so
     * a re-registered service starts with a clean relationship slate; new
     * intake answers then re-populate as needed.
     *
     * Deletes are issued in dependency order: api_consumers rows where this
     * service is the consumer go first, then apis (cascades to remaining
     * api_consumers via FK), then the service_* link tables.
     */
    public void clearAllRelationshipsForService(UUID serviceId) {
        jdbc.update("DELETE FROM api_consumers WHERE consumer_service_id = ?", serviceId);
        jdbc.update("DELETE FROM apis WHERE service_id = ?", serviceId);
        jdbc.update("DELETE FROM service_dependencies " +
                        "WHERE upstream_service_id = ? OR downstream_service_id = ?",
                serviceId, serviceId);
        jdbc.update("DELETE FROM service_databases WHERE service_id = ?", serviceId);
        jdbc.update("DELETE FROM service_external_deps WHERE service_id = ?", serviceId);
    }

    public List<ApiConsumer> findApiConsumersFor(UUID apiId) {
        return jdbc.query(
                "SELECT ac.consumer_service_id, s.name AS consumer_name, ac.description " +
                        "FROM api_consumers ac " +
                        "JOIN services s ON ac.consumer_service_id = s.id " +
                        "WHERE ac.api_id = ? " +
                        "ORDER BY s.name",
                (rs, i) -> new ApiConsumer(
                        (UUID) rs.getObject("consumer_service_id"),
                        rs.getString("consumer_name"),
                        rs.getString("description")),
                apiId);
    }

    public List<ChangeEntry> findRecentChangesFor(UUID serviceId, int limit) {
        return jdbc.query(
                "SELECT changed_at, changed_by, change_type, summary " +
                        "FROM service_changes " +
                        "WHERE service_id = ? " +
                        "ORDER BY changed_at DESC LIMIT ?",
                (rs, i) -> new ChangeEntry(
                        rs.getObject("changed_at", OffsetDateTime.class),
                        rs.getString("changed_by"),
                        rs.getString("change_type"),
                        rs.getString("summary")),
                serviceId, limit);
    }

    /**
     * Live view of external-dep observations for a service: latest per
     * {@code (service_id, external_dependency_id, source)} where
     * {@code presence='present'}. Multiple sources for the same dep return
     * multiple rows so renderers can compose intake-source descriptions with
     * pom-source coordinates. Tombstones and superseded observations are
     * excluded.
     */
    public List<ExternalDependencyUsage> findExternalDependenciesFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, external_dependency_id, description, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, external_dependency_id, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_external_deps " +
                        ") " +
                        "SELECT ed.id, ed.name, ed.url, l.description, l.source " +
                        "FROM latest l " +
                        "JOIN external_dependencies ed ON l.external_dependency_id = ed.id " +
                        "WHERE l.rn = 1 AND l.presence = 'present' AND l.service_id = ? " +
                        "ORDER BY ed.name, l.source",
                (rs, i) -> new ExternalDependencyUsage(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("url"),
                        rs.getString("description"),
                        rs.getString("source")),
                serviceId);
    }

    private ServiceDependencyEdge mapDependencyEdge(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ServiceDependencyEdge(
                (UUID) rs.getObject("upstream_service_id"),
                rs.getString("upstream_name"),
                (UUID) rs.getObject("downstream_service_id"),
                rs.getString("downstream_name"),
                rs.getString("description"));
    }

    // --- service_value_injections (Phase 5.9 M2 — append-only from day one)

    /**
     * Append one {@code @Value} injection-site observation. Defaults to
     * {@code presence='present'} with source {@code 'source-tree'}.
     * {@code modulePath} must be non-null — pass {@code ""} for root-rooted
     * services.
     */
    public UUID insertValueInjection(UUID serviceId, String modulePath,
                                      String enclosingClass, String memberName,
                                      String memberKind, String rawSpel,
                                      String keyPath, String defaultValue) {
        return insertValueInjection(serviceId, modulePath, enclosingClass, memberName,
                memberKind, rawSpel, keyPath, defaultValue, "source-tree", "present");
    }

    public UUID insertValueInjection(UUID serviceId, String modulePath,
                                      String enclosingClass, String memberName,
                                      String memberKind, String rawSpel,
                                      String keyPath, String defaultValue,
                                      String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_value_injections " +
                        "(id, service_id, module_path, enclosing_class, member_name, member_kind, " +
                        " raw_spel, key_path, default_value, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, modulePath == null ? "" : modulePath,
                enclosingClass, memberName, memberKind,
                rawSpel, keyPath, defaultValue, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for a @Value site no longer in the source tree. */
    public UUID writeValueInjectionTombstone(UUID serviceId, String modulePath,
                                              String enclosingClass, String memberName,
                                              String memberKind, String source) {
        // raw_spel + key_path are NOT NULL on the table; carry empty strings
        // for the tombstone — the renderer never surfaces tombstones.
        return insertValueInjection(serviceId, modulePath, enclosingClass, memberName,
                memberKind, "", "", null, source, "absent");
    }

    /**
     * Live view: latest observation per
     * {@code (service_id, module_path, enclosing_class, member_name, member_kind)}
     * where {@code presence='present'}. Used by the Configuration page renderer.
     */
    public List<ServiceValueInjection> findValueInjectionsFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, module_path, enclosing_class, member_name, member_kind, " +
                        "         raw_spel, key_path, default_value, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, enclosing_class, " +
                        "                            member_name, member_kind " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_value_injections " +
                        ") " +
                        "SELECT id, service_id, module_path, enclosing_class, member_name, member_kind, " +
                        "       raw_spel, key_path, default_value, source " +
                        "FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY enclosing_class, member_name",
                (rs, i) -> new ServiceValueInjection(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("module_path"),
                        rs.getString("enclosing_class"),
                        rs.getString("member_name"),
                        rs.getString("member_kind"),
                        rs.getString("raw_spel"),
                        rs.getString("key_path"),
                        rs.getString("default_value"),
                        rs.getString("source")),
                serviceId);
    }

    // --- service_configuration_properties_types (Phase 5.9 M2 — append-only from day one)

    /**
     * Append one {@code @ConfigurationProperties} type observation. Defaults
     * to {@code presence='present'} with source {@code 'source-tree'}.
     */
    public UUID insertConfigurationPropertiesType(UUID serviceId, String modulePath,
                                                   String enclosingClass, String prefix,
                                                   String typeKind, String components) {
        return insertConfigurationPropertiesType(serviceId, modulePath, enclosingClass,
                prefix, typeKind, components, "source-tree", "present");
    }

    public UUID insertConfigurationPropertiesType(UUID serviceId, String modulePath,
                                                   String enclosingClass, String prefix,
                                                   String typeKind, String components,
                                                   String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_configuration_properties_types " +
                        "(id, service_id, module_path, enclosing_class, prefix, type_kind, " +
                        " components, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, modulePath == null ? "" : modulePath,
                enclosingClass, prefix == null ? "" : prefix,
                typeKind, components, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for a @ConfigurationProperties type no longer in the source tree. */
    public UUID writeConfigurationPropertiesTypeTombstone(UUID serviceId, String modulePath,
                                                          String enclosingClass, String source) {
        // type_kind is required by the table CHECK; carry 'class' as a sensible
        // tombstone default — the renderer never surfaces tombstones.
        return insertConfigurationPropertiesType(serviceId, modulePath, enclosingClass,
                "", "class", null, source, "absent");
    }

    /**
     * Live view: latest observation per
     * {@code (service_id, module_path, enclosing_class)} where
     * {@code presence='present'}. Used by the Configuration page renderer.
     */
    public List<ServiceConfigurationPropertiesType> findConfigurationPropertiesTypesFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, module_path, enclosing_class, prefix, type_kind, " +
                        "         components, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, enclosing_class " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_configuration_properties_types " +
                        ") " +
                        "SELECT id, service_id, module_path, enclosing_class, prefix, type_kind, " +
                        "       components, source " +
                        "FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY prefix, enclosing_class",
                (rs, i) -> new ServiceConfigurationPropertiesType(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("module_path"),
                        rs.getString("enclosing_class"),
                        rs.getString("prefix"),
                        rs.getString("type_kind"),
                        rs.getString("components"),
                        rs.getString("source")),
                serviceId);
    }

    // --- service_enable_annotations (Phase 5.9 M3 — append-only from day one)

    /**
     * Append one {@code @Enable*} annotation use-site observation. Defaults
     * to {@code presence='present'} with source {@code 'source-tree'}.
     */
    public UUID insertEnableAnnotation(UUID serviceId, String modulePath,
                                        String enclosingClass,
                                        String annotationSimpleName,
                                        String annotationFqn,
                                        String javadocFirstSentence) {
        return insertEnableAnnotation(serviceId, modulePath, enclosingClass,
                annotationSimpleName, annotationFqn, javadocFirstSentence,
                "source-tree", "present");
    }

    public UUID insertEnableAnnotation(UUID serviceId, String modulePath,
                                        String enclosingClass,
                                        String annotationSimpleName,
                                        String annotationFqn,
                                        String javadocFirstSentence,
                                        String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_enable_annotations " +
                        "(id, service_id, module_path, enclosing_class, annotation_simple_name, " +
                        " annotation_fqn, javadoc_first_sentence, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, modulePath == null ? "" : modulePath,
                enclosingClass, annotationSimpleName, annotationFqn,
                javadocFirstSentence, source, presence);
        return id;
    }

    /** Append a {@code presence='absent'} tombstone for an @Enable* use-site no longer in the source tree. */
    public UUID writeEnableAnnotationTombstone(UUID serviceId, String modulePath,
                                                String enclosingClass,
                                                String annotationSimpleName,
                                                String source) {
        // annotation_fqn is required by the table schema (NOT NULL); carry
        // the simple name as a defensible default for tombstones — the
        // renderer never surfaces tombstones.
        return insertEnableAnnotation(serviceId, modulePath, enclosingClass,
                annotationSimpleName, annotationSimpleName, null, source, "absent");
    }

    /**
     * Live view: latest observation per
     * {@code (service_id, module_path, enclosing_class, annotation_simple_name)}
     * where {@code presence='present'}. Used by the Configuration page renderer.
     */
    public List<ServiceEnableAnnotation> findEnableAnnotationsFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, module_path, enclosing_class, annotation_simple_name, " +
                        "         annotation_fqn, javadoc_first_sentence, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, module_path, enclosing_class, " +
                        "                            annotation_simple_name " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_enable_annotations " +
                        ") " +
                        "SELECT id, service_id, module_path, enclosing_class, annotation_simple_name, " +
                        "       annotation_fqn, javadoc_first_sentence, source " +
                        "FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY enclosing_class, annotation_simple_name",
                (rs, i) -> new ServiceEnableAnnotation(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("module_path"),
                        rs.getString("enclosing_class"),
                        rs.getString("annotation_simple_name"),
                        rs.getString("annotation_fqn"),
                        rs.getString("javadoc_first_sentence"),
                        rs.getString("source")),
                serviceId);
    }

    // --- service_config_properties (Phase 5.9 M1 — append-only from day one)

    /**
     * Append one property-key observation. Defaults to {@code presence='present'}
     * with source {@code 'properties-file'}. Empty {@code value} is allowed
     * ({@code key=} declarations and null YAML scalars stringify to empty).
     */
    public UUID insertConfigProperty(UUID serviceId, String keyPath, String value,
                                      String sourceFile, String profile) {
        return insertConfigProperty(serviceId, keyPath, value, sourceFile, profile,
                "properties-file", "present");
    }

    public UUID insertConfigProperty(UUID serviceId, String keyPath, String value,
                                      String sourceFile, String profile,
                                      String source, String presence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_config_properties " +
                        "(id, service_id, key_path, value, source_file, profile, source, presence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, keyPath,
                value == null ? "" : value,
                sourceFile,
                profile == null ? "default" : profile,
                source, presence);
        return id;
    }

    /**
     * Append a {@code presence='absent'} tombstone for a property key no
     * longer present in the source files. The renderer never surfaces
     * tombstones; the value is bookkeeping-only.
     */
    public UUID writeConfigPropertyTombstone(UUID serviceId, String keyPath,
                                              String sourceFile, String profile,
                                              String source) {
        return insertConfigProperty(serviceId, keyPath, "", sourceFile, profile,
                source, "absent");
    }

    /**
     * Live view: latest observation per
     * {@code (service_id, key_path, profile, source_file)} where
     * {@code presence='present'}. Used by the Configuration page renderer.
     */
    public List<ServiceConfigProperty> findConfigPropertiesFor(UUID serviceId) {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, key_path, value, source_file, profile, source, presence, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, key_path, profile, source_file " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM service_config_properties " +
                        ") " +
                        "SELECT id, service_id, key_path, value, source_file, profile, source " +
                        "FROM latest " +
                        "WHERE rn = 1 AND presence = 'present' AND service_id = ? " +
                        "ORDER BY profile, key_path, source_file",
                (rs, i) -> new ServiceConfigProperty(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("key_path"),
                        rs.getString("value"),
                        rs.getString("source_file"),
                        rs.getString("profile"),
                        rs.getString("source")),
                serviceId);
    }
}
