package com.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the V1–V10 Flyway migrations against MariaDB — the production target
 * per ADR-003. The local prototype runs Postgres; this test exercises the
 * schema's portability claim, which became real in Phase 5.5 (DD-002) when
 * V1–V10 were rewritten in portable SQL.
 */
@SpringBootTest
@Testcontainers
class MariaDBPortabilitySmokeTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void whenMigrationsRunAgainstMariaDB_thenAllTenSchemaVersionsApply() {
        Long appliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Long.class);
        assertThat(appliedCount).isGreaterThanOrEqualTo(10L);
    }

    @Test
    void whenMigrationsRunAgainstMariaDB_thenAllExpectedTablesExist() {
        Long matched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name IN " +
                        "('services','apis','data_stores','external_dependencies'," +
                        "'service_dependencies','service_databases','api_consumers'," +
                        "'service_external_deps','service_changes')",
                Long.class);
        assertThat(matched).isEqualTo(9L);
    }
}
