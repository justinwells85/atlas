package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the no-sinks-enabled behavior: with every wiki sink disabled
 * via configuration, {@code SyncCoordinator} short-circuits to a no-op
 * and makes zero network calls. The work-instance Atlas (analyzing
 * confidential code with both Confluence and local-markdown disabled
 * during initial setup) relies on this guarantee.
 */
@SpringBootTest(properties = {
        "atlas.wiki.sinks.confluence.enabled=false"
})
@Testcontainers
class WikiSinkDisabledTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void confluenceProps(DynamicPropertyRegistry registry) {
        registry.add("atlas.confluence.base-url", wireMock::baseUrl);
        registry.add("atlas.confluence.email", () -> "test@example.com");
        registry.add("atlas.confluence.api-token", () -> "test-token");
        registry.add("atlas.confluence.space-key", () -> "ATLAS");
        registry.add("atlas.confluence.sync.cron", () -> "-");
    }

    @Autowired
    SyncCoordinator coordinator;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM services");
        jdbc.update("DELETE FROM service_changes");
    }

    @Test
    void whenNoSinksEnabledAndServicesExist_thenSyncReportsZeroSuccessAndZeroFailure() {
        Service s = new Service();
        s.setName("billing-service");
        s.setStatus(ServiceStatus.ACTIVE);
        serviceRepository.save(s);

        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void whenNoSinksEnabled_thenZeroNetworkCallsHappen() {
        Service s = new Service();
        s.setName("billing-service");
        s.setStatus(ServiceStatus.ACTIVE);
        serviceRepository.save(s);

        coordinator.syncAll();

        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void whenNoSinksEnabledAndDbEmpty_thenSyncReportsZeroSuccess() {
        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }
}
