package com.atlas.codesync;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing smoke tests for {@link CodeSyncController}: verifies path + verb +
 * path-variable binding + JSON body shape for every refresh endpoint. The
 * service under test has no {@code repo_url} and no {@code openapi_spec_url},
 * so each endpoint takes its short-circuit path and returns
 * {@link CodeSyncResult#empty()} — no HTTP mocks needed. The behavior of
 * each refresh path is covered by {@link CodeSyncCoordinatorTest}; these
 * tests catch wiring regressions (typo in path, swapped HTTP verb, renamed
 * path variable, broken JSON marshaling) that would otherwise only surface
 * at integration time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CodeSyncControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ServiceRepository serviceRepository;

    private UUID serviceId;

    @BeforeEach
    void seedService() {
        Service s = new Service();
        s.setName("controller-smoke-svc-" + UUID.randomUUID());
        s.setOwnerTeam("platform");
        s.setStatus(ServiceStatus.ACTIVE);
        // repo_url and openapi_spec_url left null on purpose: each endpoint
        // short-circuits to CodeSyncResult.empty() before touching any HTTP
        // seam, so no WireMock setup is needed for routing assertions.
        serviceId = serviceRepository.saveAndFlush(s).getId();
    }

    @Test
    void postRefreshOpenApi_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/refresh/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void postRefreshTests_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/refresh-tests/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void postRefreshPom_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/refresh-pom/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void postRefreshBeans_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/refresh-beans/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void postRefreshConfiguration_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/refresh-configuration/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void postTombstoneStaleIntakeApis_routesToCoordinatorAndReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/api/code-sync/tombstone-stale-intake-apis/{serviceId}", serviceId))
                .andExpect(status().isOk())
                .andExpectAll(emptyResultJson());
    }

    @Test
    void getOnRefreshEndpoint_returnsMethodNotAllowed() throws Exception {
        // Guard against a verb regression — refresh endpoints are POST-only.
        mockMvc.perform(get("/api/code-sync/refresh-configuration/{serviceId}", serviceId))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void postWithMalformedServiceIdPathVariable_returnsBadRequest() throws Exception {
        // Asserts the {serviceId} binding actually runs UUID parsing — a
        // typo'd path variable name in the controller would silently match
        // on the literal string instead.
        mockMvc.perform(post("/api/code-sync/refresh-configuration/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.ResultMatcher[] emptyResultJson() {
        return new org.springframework.test.web.servlet.ResultMatcher[] {
                jsonPath("$.created").value(0),
                jsonPath("$.updated").value(0),
                jsonPath("$.deleted").value(0),
                jsonPath("$.skipped").value(0)
        };
    }
}
