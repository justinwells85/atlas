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
class EntityTablesSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    JdbcTemplate jdbcTemplate;

    // --- apis ---------------------------------------------------------------

    @Test
    void whenApiHasValidService_thenInsertSucceeds() {
        UUID serviceId = insertService("svc-with-api");

        jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method, auth_method) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, "/v1/widgets", "GET", "api-key");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE service_id = ?",
                Long.class, serviceId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenApiReferencesMissingService_thenFkViolationIsRaised() {
        UUID bogusServiceId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), bogusServiceId, "/v1/orphan", "GET"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenApiDuplicatesServicePathMethod_thenUniqueViolationIsRaised() {
        UUID serviceId = insertService("svc-dup-api");
        jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, "/v1/dupes", "POST");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, "/v1/dupes", "POST"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenServiceWithApisIsDeleted_thenApisAreDeleted() {
        UUID serviceId = insertService("svc-cascade");
        jdbcTemplate.update(
                "INSERT INTO apis (id, service_id, path, method) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), serviceId, "/v1/cascades", "GET");

        jdbcTemplate.update("DELETE FROM services WHERE id = ?", serviceId);

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apis WHERE service_id = ?",
                Long.class, serviceId);
        assertThat(remaining).isEqualTo(0L);
    }

    // --- databases ----------------------------------------------------------

    @Test
    void whenDatabaseIsInserted_thenItPersists() {
        jdbcTemplate.update(
                "INSERT INTO data_stores (id, name, engine, owner_team, data_classification) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "users-db", "postgres", "platform", "pii");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_stores WHERE name = 'users-db'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenDatabaseNameIsDuplicate_thenUniqueViolationIsRaised() {
        jdbcTemplate.update("INSERT INTO data_stores (id, name) VALUES (?, ?)",
                UUID.randomUUID(), "shared-db");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO data_stores (id, name) VALUES (?, ?)",
                UUID.randomUUID(), "shared-db"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- external_dependencies ---------------------------------------------

    @Test
    void whenExternalDependencyIsInserted_thenItPersists() {
        jdbcTemplate.update(
                "INSERT INTO external_dependencies (id, name, url, description) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "Stripe", "https://stripe.com", "Payments");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_dependencies WHERE name = 'Stripe'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenExternalDependencyNameIsDuplicate_thenUniqueViolationIsRaised() {
        jdbcTemplate.update(
                "INSERT INTO external_dependencies (id, name) VALUES (?, ?)",
                UUID.randomUUID(), "SendGrid");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO external_dependencies (id, name) VALUES (?, ?)",
                UUID.randomUUID(), "SendGrid"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ------------------------------------------------------------

    private UUID insertService(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO services (id, name) VALUES (?, ?)", id, name);
        return id;
    }
}
