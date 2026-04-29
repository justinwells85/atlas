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
                          String presence, String confluencePageId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO apis (id, service_id, path, method, auth_method, description, source, presence, confluence_page_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, serviceId, path, method, authMethod, description, source, presence, confluencePageId);
        return id;
    }

    /**
     * Convenience overload for the common "live observation, no page id yet" case.
     * Equivalent to {@link #insertApi(UUID, String, String, String, String, String, String, String)}
     * with {@code presence='present'} and {@code confluencePageId=null}.
     */
    public UUID insertApi(UUID serviceId, String path, String method,
                          String authMethod, String description, String source) {
        return insertApi(serviceId, path, method, authMethod, description, source, "present", null);
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
     * Tombstone rows whose Confluence pages still need to be removed: latest
     * observation per key is {@code presence='absent'} and carries a non-null
     * {@code confluence_page_id}. Bypasses the live-only filter. Only includes
     * tombstones whose owning service is itself still live (soft-deleted
     * services are handled by {@code cleanupDeletedServices}).
     */
    public List<SoftDeletedApiPage> findStaleApiPages() {
        return jdbc.query(
                "WITH latest AS (" +
                        "  SELECT id, service_id, method, path, source, presence, confluence_page_id, " +
                        "         ROW_NUMBER() OVER (PARTITION BY service_id, method, path, source " +
                        "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                        "  FROM apis " +
                        ") " +
                        "SELECT l.id, l.method, l.path, l.confluence_page_id, " +
                        "       s.id AS service_id, s.name AS service_name " +
                        "FROM latest l " +
                        "JOIN services s ON s.id = l.service_id " +
                        "WHERE l.rn = 1 AND l.presence = 'absent' " +
                        "  AND l.confluence_page_id IS NOT NULL " +
                        "  AND s.deleted_at IS NULL " +
                        "ORDER BY s.name, l.method, l.path",
                (rs, i) -> new SoftDeletedApiPage(
                        (UUID) rs.getObject("id"),
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getString("confluence_page_id"),
                        (UUID) rs.getObject("service_id"),
                        rs.getString("service_name")));
    }

    /** Null an api row's confluence_page_id. Used by the cleanup pass after the page is gone in Confluence. */
    public void clearApiConfluencePageId(UUID apiId) {
        jdbc.update("UPDATE apis SET confluence_page_id = NULL WHERE id = ?", apiId);
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
                "         presence, confluence_page_id, " +
                "         ROW_NUMBER() OVER (PARTITION BY service_id, method, path, source " +
                "                            ORDER BY observed_at DESC, id DESC) AS rn " +
                "  FROM apis " +
                ") " +
                "SELECT id, path, method, auth_method, description, source, confluence_page_id " +
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
                rs.getString("confluence_page_id"));
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

    /** Update services.tests_page_id after the sync coordinator creates the per-service tests page. */
    public void setServiceTestsPageId(UUID serviceId, String pageId) {
        jdbc.update(
                "UPDATE services SET tests_page_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                pageId, serviceId);
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

    public List<ExternalDependencyUsage> findExternalDependenciesFor(UUID serviceId) {
        return jdbc.query(
                "SELECT ed.id, ed.name, ed.url, sed.description " +
                        "FROM service_external_deps sed " +
                        "JOIN external_dependencies ed ON sed.external_dependency_id = ed.id " +
                        "WHERE sed.service_id = ? " +
                        "ORDER BY ed.name",
                (rs, i) -> new ExternalDependencyUsage(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("url"),
                        rs.getString("description")),
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
}
