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
class AtlasApplicationSmokeTest {

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
    void whenApplicationStarts_thenV1MigrationIsApplied() {
        Boolean v1Success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'",
                Boolean.class);
        assertThat(v1Success).isTrue();

        Long matchedColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'services' AND column_name IN " +
                        "('id','name','description','owner_team','status','language','framework'," +
                        "'repo_url','deployment','support_contact','sla','notes','metadata'," +
                        "'confluence_page_id','last_synced_to_confluence','created_at','updated_at')",
                Long.class);
        assertThat(matchedColumnCount).isEqualTo(17L);
    }
}
