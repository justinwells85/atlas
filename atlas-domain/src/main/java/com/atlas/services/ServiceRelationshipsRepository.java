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

    /** Insert one row in {@code apis}. Returns the generated id. */
    public UUID insertApi(UUID serviceId, String path, String method,
                          String authMethod, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO apis (id, service_id, path, method, auth_method, description) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                id, serviceId, path, method, authMethod, description);
        return id;
    }

    /** Insert one row in {@code api_consumers} linking an API to a consumer service. */
    public void insertApiConsumer(UUID apiId, UUID consumerServiceId, String description) {
        jdbc.update(
                "INSERT INTO api_consumers (api_id, consumer_service_id, description) " +
                        "VALUES (?, ?, ?)",
                apiId, consumerServiceId, description);
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
                "INSERT INTO service_changes (service_id, changed_by, change_type, summary) " +
                        "VALUES (?, ?, ?, ?)",
                serviceId, changedBy, changeType, summary);
    }

    /** Insert one directed edge in {@code service_dependencies}. */
    public void insertServiceDependency(UUID upstreamServiceId, UUID downstreamServiceId,
                                        String description) {
        jdbc.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id, description) " +
                        "VALUES (?, ?, ?)",
                upstreamServiceId, downstreamServiceId, description);
    }

    /** Lookup an existing database by name. */
    public Optional<UUID> findDatabaseIdByName(String name) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM databases WHERE name = ?",
                (rs, i) -> (UUID) rs.getObject("id"),
                name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Insert one row in {@code databases}. Returns the generated id. */
    public UUID insertDatabase(String name, String engine) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO databases (id, name, engine) VALUES (?, ?, ?)",
                id, name, engine);
        return id;
    }

    /** Insert one row linking a service to a database (with ownership flag). */
    public void insertServiceDatabaseLink(UUID serviceId, UUID databaseId,
                                          boolean isOwner, String description) {
        jdbc.update(
                "INSERT INTO service_databases " +
                        "(service_id, database_id, is_owner, description) " +
                        "VALUES (?, ?, ?, ?)",
                serviceId, databaseId, isOwner, description);
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
                        "(service_id, external_dependency_id, description) " +
                        "VALUES (?, ?, ?)",
                serviceId, externalDependencyId, description);
    }

    // --- Reads (Phase 3) ----------------------------------------------------

    public List<ApiSummary> findApisFor(UUID serviceId) {
        return jdbc.query(
                "SELECT id, path, method, auth_method, description " +
                        "FROM apis WHERE service_id = ? ORDER BY path, method",
                (rs, i) -> new ApiSummary(
                        (UUID) rs.getObject("id"),
                        rs.getString("path"),
                        rs.getString("method"),
                        rs.getString("auth_method"),
                        rs.getString("description")),
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
                        "JOIN databases db ON sdb.database_id = db.id " +
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
