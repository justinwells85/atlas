package com.atlas.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to a service's relationship rows: APIs it exposes,
 * databases it uses, third-party dependencies, and upstream/downstream
 * service-to-service edges. JdbcTemplate (not JPA) so the joins stay
 * obvious and the read shape is independent of any future entity mappings.
 *
 * Writes to relationship tables are deferred to Phase 3.5; Phase 3 only
 * needs the read paths to be in place so Confluence pages can render
 * structured data once Phase 3.5 populates the rows.
 */
@Repository
public class ServiceRelationshipsRepository {

    private final JdbcTemplate jdbc;

    public ServiceRelationshipsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
