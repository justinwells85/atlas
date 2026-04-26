package com.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Transactional
class RelationshipAndAuditSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    JdbcTemplate jdbcTemplate;

    // --- service_dependencies ----------------------------------------------

    @Test
    void whenServiceDependencyIsValid_thenInsertSucceeds() {
        UUID upstream = insertService("svc-upstream");
        UUID downstream = insertService("svc-downstream");

        jdbcTemplate.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id, description) " +
                        "VALUES (?, ?, ?)",
                upstream, downstream, "downstream calls upstream's REST API");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_dependencies " +
                        "WHERE upstream_service_id = ? AND downstream_service_id = ?",
                Long.class, upstream, downstream);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenServiceDependencyIsSelfReferential_thenCheckViolationIsRaised() {
        UUID svc = insertService("svc-self");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id) VALUES (?, ?)",
                svc, svc))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenServiceDependencyIsDuplicate_thenUniqueViolationIsRaised() {
        UUID upstream = insertService("svc-up-dup");
        UUID downstream = insertService("svc-down-dup");
        jdbcTemplate.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id) VALUES (?, ?)",
                upstream, downstream);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id) VALUES (?, ?)",
                upstream, downstream))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenUpstreamServiceIsDeleted_thenDependenciesAreDeleted() {
        UUID upstream = insertService("svc-up-cascade");
        UUID downstream = insertService("svc-down-cascade");
        jdbcTemplate.update(
                "INSERT INTO service_dependencies " +
                        "(upstream_service_id, downstream_service_id) VALUES (?, ?)",
                upstream, downstream);

        jdbcTemplate.update("DELETE FROM services WHERE id = ?", upstream);

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_dependencies " +
                        "WHERE upstream_service_id = ? OR downstream_service_id = ?",
                Long.class, upstream, upstream);
        assertThat(remaining).isEqualTo(0L);
    }

    // --- service_databases --------------------------------------------------

    @Test
    void whenServiceDatabaseLinkIsInserted_thenItPersists() {
        UUID svc = insertService("svc-db-user");
        UUID db = insertDatabase("db-shared");

        jdbcTemplate.update(
                "INSERT INTO service_databases (service_id, database_id, is_owner, description) " +
                        "VALUES (?, ?, ?, ?)",
                svc, db, true, "primary read/write");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_databases " +
                        "WHERE service_id = ? AND database_id = ?",
                Long.class, svc, db);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenServiceDatabaseLinkIsDuplicate_thenUniqueViolationIsRaised() {
        UUID svc = insertService("svc-db-dup");
        UUID db = insertDatabase("db-dup");
        jdbcTemplate.update(
                "INSERT INTO service_databases (service_id, database_id) VALUES (?, ?)",
                svc, db);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO service_databases (service_id, database_id) VALUES (?, ?)",
                svc, db))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- api_consumers ------------------------------------------------------

    @Test
    void whenApiConsumerLinkIsInserted_thenItPersists() {
        UUID provider = insertService("svc-api-provider");
        UUID consumer = insertService("svc-api-consumer");
        UUID api = insertApi(provider, "/v1/things", "GET");

        jdbcTemplate.update(
                "INSERT INTO api_consumers (api_id, consumer_service_id, description) " +
                        "VALUES (?, ?, ?)",
                api, consumer, "fetches things on every page render");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_consumers " +
                        "WHERE api_id = ? AND consumer_service_id = ?",
                Long.class, api, consumer);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenApiConsumerLinkIsDuplicate_thenUniqueViolationIsRaised() {
        UUID provider = insertService("svc-api-dup-provider");
        UUID consumer = insertService("svc-api-dup-consumer");
        UUID api = insertApi(provider, "/v1/dup", "GET");
        jdbcTemplate.update(
                "INSERT INTO api_consumers (api_id, consumer_service_id) VALUES (?, ?)",
                api, consumer);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO api_consumers (api_id, consumer_service_id) VALUES (?, ?)",
                api, consumer))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- service_external_deps ---------------------------------------------

    @Test
    void whenServiceExternalDepLinkIsInserted_thenItPersists() {
        UUID svc = insertService("svc-ext-user");
        UUID dep = insertExternalDep("Stripe-link");

        jdbcTemplate.update(
                "INSERT INTO service_external_deps " +
                        "(service_id, external_dependency_id, description) VALUES (?, ?, ?)",
                svc, dep, "process card payments");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_external_deps " +
                        "WHERE service_id = ? AND external_dependency_id = ?",
                Long.class, svc, dep);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenServiceExternalDepLinkIsDuplicate_thenUniqueViolationIsRaised() {
        UUID svc = insertService("svc-ext-dup");
        UUID dep = insertExternalDep("SendGrid-link");
        jdbcTemplate.update(
                "INSERT INTO service_external_deps " +
                        "(service_id, external_dependency_id) VALUES (?, ?)",
                svc, dep);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO service_external_deps " +
                        "(service_id, external_dependency_id) VALUES (?, ?)",
                svc, dep))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- service_changes (audit) -------------------------------------------

    @Test
    void whenChangeIsLogged_thenItPersists() {
        UUID svc = insertService("svc-audit-1");

        jdbcTemplate.update(
                "INSERT INTO service_changes " +
                        "(service_id, changed_by, change_type, summary) VALUES (?, ?, ?, ?)",
                svc, "intake-agent", "created", "service registered via intake");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_changes WHERE service_id = ?",
                Long.class, svc);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenServiceIsDeleted_thenItsChangeHistorySurvives() {
        UUID svc = insertService("svc-audit-2");
        jdbcTemplate.update(
                "INSERT INTO service_changes " +
                        "(service_id, changed_by, change_type, summary) VALUES (?, ?, ?, ?)",
                svc, "user@example.com", "updated", "owner_team changed");

        jdbcTemplate.update("DELETE FROM services WHERE id = ?", svc);

        Long surviving = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_changes WHERE service_id = ?",
                Long.class, svc);
        assertThat(surviving).isEqualTo(1L);
    }

    // --- helpers ------------------------------------------------------------

    private UUID insertService(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO services (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertDatabase(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO databases (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertExternalDep(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO external_dependencies (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID insertApi(UUID serviceId, String path, String method) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method) VALUES (?, ?, ?, ?)",
                id, serviceId, path, method);
        return id;
    }
}
