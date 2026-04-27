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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SyncCoordinatorIntegrationTest {

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

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        serviceRepository.deleteAll();
    }

    @Test
    void whenServiceHasNoPageId_thenCreatesPageAndPersistsId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("""
                        {"id":"NEW123","title":"billing-service"}
                        """)));

        Service s = new Service();
        s.setName("billing-service");
        s.setDescription("Handles billing");
        s.setStatus(ServiceStatus.ACTIVE);
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("NEW123");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("billing-service")))
                .withRequestBody(matchingJsonPath("$.spaceId", equalTo("589827")))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage"))));
    }

    @Test
    void whenServiceAlreadyHasPageId_thenUpdatesPageAndKeepsId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .willReturn(okJson("""
                        {"id":"EXISTING123","title":"checkout-service","version":{"number":3}}
                        """)));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .willReturn(okJson("""
                        {"id":"EXISTING123","title":"checkout-service","version":{"number":4}}
                        """)));

        Service s = new Service();
        s.setName("checkout-service");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("EXISTING123");
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("EXISTING123");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/EXISTING123"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("checkout-service")))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage")))
                .withRequestBody(matchingJsonPath("$.body.value", containing("<h2>Overview</h2>")))
                .withRequestBody(matchingJsonPath("$.version.number", equalTo("4"))));
    }

    @Test
    void whenUpdateReturns404_thenCoordinatorRecreatesPageAndPersistsNewId() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/STALE_ID"))
                .willReturn(aResponse().withStatus(404).withBody("page deleted")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(okJson("""
                        {"id":"FRESH_ID"}
                        """)));

        Service s = new Service();
        s.setName("orphan-service");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConfluencePageId("STALE_ID");
        Service saved = serviceRepository.save(s);

        SyncResult result = coordinator.syncOne(saved.getId());

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();

        Service reloaded = serviceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfluencePageId()).isEqualTo("FRESH_ID");
        assertThat(reloaded.getLastSyncedToConfluence()).isNotNull();

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("orphan-service"))));
    }

    @Test
    void whenSyncAllOnEmptyDb_thenReturnsZeroCountsAndMakesNoConfluenceCalls() {
        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v2/spaces")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v2/pages")));
    }

    @Test
    void whenSyncAllAndOneServiceFails_thenOthersStillSyncAndFailureIsReported() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("svc-good-1")))
                .willReturn(okJson("""
                        {"id":"PAGE_GOOD_1"}
                        """)));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("svc-bad")))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("svc-good-2")))
                .willReturn(okJson("""
                        {"id":"PAGE_GOOD_2"}
                        """)));

        Service good1 = createService("svc-good-1");
        Service bad = createService("svc-bad");
        Service good2 = createService("svc-good-2");

        SyncResult result = coordinator.syncAll();

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures()).extracting(SyncFailure::serviceName)
                .containsExactly("svc-bad");

        assertThat(serviceRepository.findById(good1.getId()).orElseThrow().getConfluencePageId())
                .isEqualTo("PAGE_GOOD_1");
        assertThat(serviceRepository.findById(good2.getId()).orElseThrow().getConfluencePageId())
                .isEqualTo("PAGE_GOOD_2");
        assertThat(serviceRepository.findById(bad.getId()).orElseThrow().getConfluencePageId())
                .isNull();
    }

    @Test
    void whenSyncOneCalledOnUnknownServiceId_thenThrows() {
        java.util.UUID nonexistent = java.util.UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.syncOne(nonexistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(nonexistent.toString());
    }

    private Service createService(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return serviceRepository.save(s);
    }
}
