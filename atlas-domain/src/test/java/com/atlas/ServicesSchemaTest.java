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
class ServicesSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void whenServiceStatusIsValid_thenInsertSucceeds() {
        jdbcTemplate.update("INSERT INTO services (id, name, status) VALUES (?, ?, ?)",
                UUID.randomUUID(), "valid-active", "active");
        jdbcTemplate.update("INSERT INTO services (id, name, status) VALUES (?, ?, ?)",
                UUID.randomUUID(), "valid-deprecated", "deprecated");
        jdbcTemplate.update("INSERT INTO services (id, name, status) VALUES (?, ?, ?)",
                UUID.randomUUID(), "valid-in-dev", "in_dev");

        Long matched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM services WHERE name LIKE 'valid-%'",
                Long.class);
        assertThat(matched).isEqualTo(3L);
    }

    @Test
    void whenServiceStatusIsInvalid_thenInsertIsRejectedByDb() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO services (id, name, status) VALUES (?, ?, ?)",
                UUID.randomUUID(), "invalid-status", "bogus"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenServicesStatusColumn_thenItIsTextNotEnum() {
        // Per ADR-009: status is TEXT + CHECK so the schema ports unchanged
        // to MySQL/MariaDB. A Postgres-specific ENUM would show up here as
        // 'USER-DEFINED' instead of 'text'.
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'services' AND column_name = 'status'",
                String.class);
        assertThat(dataType).isEqualTo("text");
    }

    // The pre-Phase-5.5 V2 dropped a Postgres ENUM type; that check via
    // pg_type was Postgres-only and is now redundant since V1 itself never
    // creates the ENUM. The "TEXT not ENUM" property is asserted portably
    // above via information_schema.columns.

    // ADR-008 updated_at @PreUpdate behavior is asserted in ServiceRepositoryTest
    // (the JPA path); raw SQL writes intentionally do not bump updated_at.
}
