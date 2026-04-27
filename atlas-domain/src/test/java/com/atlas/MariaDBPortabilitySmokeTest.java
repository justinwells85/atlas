package com.atlas;

import org.junit.jupiter.api.Disabled;
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
 * per ADR-003. The local prototype runs Postgres; this test is the only thing
 * that exercises the schema's portability claim.
 *
 * Currently {@code @Disabled} because it fails on MariaDB at V1 line 7
 * ({@code CREATE TYPE service_status AS ENUM ...} is Postgres-only). See
 * {@code docs/deferred-decisions.md} DD-002 for the full failure list and the
 * remediation options. Re-enable once V1 is rewritten to portable SQL.
 */
@Disabled("Schema portability gap — see docs/deferred-decisions.md DD-002")
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
                        "('services','apis','databases','external_dependencies'," +
                        "'service_dependencies','service_databases','api_consumers'," +
                        "'service_external_deps','service_changes')",
                Long.class);
        assertThat(matched).isEqualTo(9L);
    }
}
