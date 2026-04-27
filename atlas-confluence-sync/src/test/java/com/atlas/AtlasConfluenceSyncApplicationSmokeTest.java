package com.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class AtlasConfluenceSyncApplicationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void whenApplicationStarts_thenContextLoadsWithoutErrors() {
    }

    @Test
    void whenApplicationStarts_thenDataSourceCanRunQueries() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void whenApplicationStarts_thenAllMigrationsApplied() {
        Long appliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Long.class);
        assertThat(appliedCount).isGreaterThanOrEqualTo(10L);

        Long syncColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'services' AND column_name IN " +
                        "('confluence_page_id','last_synced_to_confluence')",
                Long.class);
        assertThat(syncColumnCount).isEqualTo(2L);
    }
}
