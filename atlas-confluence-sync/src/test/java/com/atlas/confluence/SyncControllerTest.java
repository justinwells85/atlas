package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SyncControllerTest {

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
    MockMvc mvc;

    @Autowired
    ServiceRepository serviceRepository;

    private static final String LANDING_ID = "LANDING";

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        serviceRepository.deleteAll();
    }

    private void stubLandingPageExists() {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/pages"))
                .withQueryParam("title", WireMock.containing("Atlas"))
                .willReturn(WireMock.okJson("{\"results\":[{\"id\":\"" + LANDING_ID + "\"}]}")));
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/pages/" + LANDING_ID))
                .willReturn(WireMock.okJson("{\"id\":\"" + LANDING_ID + "\",\"version\":{\"number\":1}}")));
        wireMock.stubFor(WireMock.put(WireMock.urlPathEqualTo("/api/v2/pages/" + LANDING_ID))
                .willReturn(WireMock.okJson("{\"id\":\"" + LANDING_ID + "\",\"version\":{\"number\":2}}")));
    }

    @Test
    void whenPostingToSyncRun_thenAllServicesSyncedAndCountsReturned() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/spaces"))
                .willReturn(WireMock.okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(WireMock.matchingJsonPath("$.title", WireMock.equalTo("Service: svc-x")))
                .willReturn(WireMock.okJson("""
                        {"id":"NEW123"}
                        """)));

        Service s = new Service();
        s.setName("svc-x");
        s.setStatus(ServiceStatus.ACTIVE);
        serviceRepository.save(s);

        mvc.perform(post("/api/sync/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount", is(1)))
                .andExpect(jsonPath("$.failureCount", is(0)))
                .andExpect(jsonPath("$.failures", empty()));
    }

    @Test
    void whenPostingToSyncRunWithServiceId_thenOnlyThatServiceIsSynced() throws Exception {
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/spaces"))
                .willReturn(WireMock.okJson("""
                        {"results":[{"id":"589827","key":"ATLAS"}]}
                        """)));
        stubLandingPageExists();
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v2/pages"))
                .withRequestBody(WireMock.matchingJsonPath("$.title", WireMock.equalTo("Service: svc-one")))
                .willReturn(WireMock.okJson("""
                        {"id":"PAGE_X"}
                        """)));

        Service one = createService("svc-one");
        Service two = createService("svc-two");

        mvc.perform(post("/api/sync/run/" + one.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount", is(1)));

        Service oneReloaded = serviceRepository.findById(one.getId()).orElseThrow();
        assertThat(oneReloaded.getConfluencePageId()).isEqualTo("PAGE_X");

        Service twoReloaded = serviceRepository.findById(two.getId()).orElseThrow();
        assertThat(twoReloaded.getConfluencePageId()).isNull();
    }

    @Test
    void whenPostingToSyncRunWithUnknownServiceId_thenReturns400WithErrorBody() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(post("/api/sync/run/" + unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString(unknown.toString())));
    }

    private Service createService(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return serviceRepository.save(s);
    }
}
