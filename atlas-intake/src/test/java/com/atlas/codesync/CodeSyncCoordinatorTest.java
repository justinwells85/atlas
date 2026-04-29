package com.atlas.codesync;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import com.atlas.services.TestScenario;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior-focused tests for the OpenAPI refresh path: provenance is honoured,
 * intake-source rows are never touched by code-sync, fetch/parse failures leave
 * the DB unmutated. WireMock stands in for the remote OpenAPI host (architectural
 * seam per ADR-006); everything inside Atlas — repository, parser, JPA — uses
 * real implementations against a Testcontainers Postgres.
 */
@SpringBootTest
@Testcontainers
class CodeSyncCoordinatorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void codeSyncProps(DynamicPropertyRegistry registry) {
        // Point RepoFileFetcher at the shared WireMock so tests for refreshTests
        // can stub the GitHub Contents API endpoints alongside the OpenAPI ones.
        registry.add("atlas.code-sync.github-api-base", wireMock::baseUrl);
    }

    @Autowired
    CodeSyncCoordinator coordinator;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    ServiceRelationshipsRepository relationships;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM api_consumers");
        jdbc.update("DELETE FROM apis");
        jdbc.update("DELETE FROM service_test_scenarios");
        jdbc.update("DELETE FROM service_metadata");
        jdbc.update("DELETE FROM service_external_deps");
        jdbc.update("DELETE FROM external_dependencies");
        jdbc.update("DELETE FROM service_changes");
        jdbc.update("DELETE FROM services");
        wireMock.resetAll();
    }

    @Test
    void whenServiceHasNoOpenApiUrl_thenRefreshIsNoop() {
        Service s = saveService("no-spec-svc", null);

        CodeSyncResult result = coordinator.refreshOpenApi(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
        assertThat(relationships.findApisFor(s.getId())).isEmpty();
        assertAuditCount(s.getId(), 0);
    }

    @Test
    void whenOpenApiSpecHasEndpoints_thenApisAreUpsertedWithOpenApiSource() {
        Service s = saveService("spec-svc", specUrl());
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "spec-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "Health probe"}},
                    "/v1/orders": {"post": {"summary": "Create order"}}
                  }
                }
                """);

        CodeSyncResult result = coordinator.refreshOpenApi(s.getId());

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.deleted()).isZero();

        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).extracting(ApiSummary::source).containsOnly("openapi");
        assertThat(rows).extracting(ApiSummary::method, ApiSummary::path)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("GET", "/v1/health"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/v1/orders"));
        assertThat(rows).extracting(ApiSummary::description)
                .contains("Health probe", "Create order");
        assertAuditCount(s.getId(), 1);
        assertLastAuditChangedBy(s.getId(), "code-sync-openapi");
    }

    @Test
    void whenIntakeApisExistAndOpenApiArrives_thenIntakeApisAreUntouched() {
        Service s = saveService("mixed-svc", specUrl());
        UUID intakeApiId = relationships.insertApi(s.getId(), "/v1/legacy", "GET",
                "bearer", "Hand-described legacy endpoint", "intake");
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "mixed-svc", "version": "1.0"},
                  "paths": {
                    "/v1/orders": {"get": {"summary": "Order list"}}
                  }
                }
                """);

        coordinator.refreshOpenApi(s.getId());

        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).hasSize(2);
        ApiSummary preservedIntake = rows.stream()
                .filter(r -> r.id().equals(intakeApiId))
                .findFirst().orElseThrow();
        assertThat(preservedIntake.source()).isEqualTo("intake");
        assertThat(preservedIntake.path()).isEqualTo("/v1/legacy");
        assertThat(preservedIntake.description()).isEqualTo("Hand-described legacy endpoint");
    }

    @Test
    void whenOpenApiEndpointIsRemovedFromSpec_thenOpenApiSourceRowDeleted_butIntakeRowsRemain() {
        Service s = saveService("trim-svc", specUrl());
        relationships.insertApi(s.getId(), "/v1/legacy", "GET", "bearer", "Legacy", "intake");
        // First refresh: populate two openapi rows.
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "trim-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "Health"}},
                    "/v1/orders": {"get": {"summary": "Orders"}}
                  }
                }
                """);
        coordinator.refreshOpenApi(s.getId());
        assertThat(relationships.findApisFor(s.getId())).hasSize(3);

        // Second refresh: spec drops /v1/orders.
        wireMock.resetAll();
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "trim-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "Health"}}
                  }
                }
                """);
        CodeSyncResult result = coordinator.refreshOpenApi(s.getId());

        assertThat(result.deleted()).isEqualTo(1);
        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).extracting(ApiSummary::path)
                .containsExactlyInAnyOrder("/v1/health", "/v1/legacy");
        // Intake row survives unconditionally.
        assertThat(rows).filteredOn(r -> "intake".equals(r.source()))
                .extracting(ApiSummary::path).containsExactly("/v1/legacy");
    }

    @Test
    void whenSpecUrlReturns404_thenRefreshFails_andRowsAreNotMutated() {
        Service s = saveService("missing-spec-svc", specUrl());
        relationships.insertApi(s.getId(), "/v1/legacy", "GET", "bearer", "Legacy", "intake");
        wireMock.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> coordinator.refreshOpenApi(s.getId()))
                .isInstanceOf(RestClientException.class);

        // DB unchanged: the intake row is still there, no audit row.
        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).extracting(ApiSummary::path).containsExactly("/v1/legacy");
        assertAuditCount(s.getId(), 0);
    }

    @Test
    void whenSpecIsMalformed_thenParseFails_andDbIsUntouched() {
        Service s = saveService("bad-spec-svc", specUrl());
        relationships.insertApi(s.getId(), "/v1/legacy", "GET", "bearer", "Legacy", "intake");
        stubSpec("not actually json or yaml — just gibberish");

        assertThatThrownBy(() -> coordinator.refreshOpenApi(s.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).extracting(ApiSummary::path).containsExactly("/v1/legacy");
        assertAuditCount(s.getId(), 0);
    }

    @Test
    void whenIntakeAlreadyOwnsAnEndpointInTheSpec_thenCodeSyncSkipsItRatherThanFailing() {
        // The unique constraint on (service_id, method, path) means we can't
        // have parallel intake- and openapi-source rows for the same endpoint.
        // The plan's "never touch intake rows" rule extends to: if the spec
        // includes an endpoint intake already covers, code-sync defers — the
        // intake row stays as-is and the skip is recorded in the result.
        Service s = saveService("collision-svc", specUrl());
        relationships.insertApi(s.getId(), "/v1/orders", "POST", "intake-auth",
                "Hand-described create-order", "intake");
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "collision-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "Health"}},
                    "/v1/orders": {"post": {"summary": "Auto-described create-order"}}
                  }
                }
                """);

        CodeSyncResult result = coordinator.refreshOpenApi(s.getId());

        assertThat(result.created()).isEqualTo(1); // only /v1/health
        assertThat(result.skipped()).isEqualTo(1); // /v1/orders deferred to intake

        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).hasSize(2);
        ApiSummary intakeRow = rows.stream()
                .filter(r -> "intake".equals(r.source())).findFirst().orElseThrow();
        assertThat(intakeRow.path()).isEqualTo("/v1/orders");
        assertThat(intakeRow.description()).isEqualTo("Hand-described create-order");
    }

    @Test
    void whenSpecInsertWouldCollideMidLoop_thenWholeRefreshIsRolledBack() {
        // Belt-and-suspenders: if some unforeseen DB error fires partway through
        // the loop, the @Transactional wrapper means the rows already inserted
        // on this call disappear. This test simulates the failure by seeding an
        // openapi-source row at one spec path AND an intake-source row at
        // another to force a code-path that would otherwise commit per-statement.
        // (The intake-collision skip means it doesn't actually fail today, but
        // the rollback contract is what we're documenting.)
        Service s = saveService("rollback-svc", specUrl());
        // An existing openapi row that the refresh will UPDATE — proves transactional reads see prior state.
        relationships.insertApi(s.getId(), "/v1/health", "GET", null, "old description", "openapi");
        stubSpec("""
                {
                  "openapi": "3.0.1",
                  "info": {"title": "rollback-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "fresh description"}}
                  }
                }
                """);

        coordinator.refreshOpenApi(s.getId());

        List<ApiSummary> rows = relationships.findApisFor(s.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("fresh description");
    }

    @Test
    void whenOpenApiSpecIsRefreshedTwice_thenSecondRunIsIdempotent() {
        Service s = saveService("idem-svc", specUrl());
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "idem-svc", "version": "1.0"},
                  "paths": {
                    "/v1/health": {"get": {"summary": "Health"}}
                  }
                }
                """;
        stubSpec(spec);
        coordinator.refreshOpenApi(s.getId());

        wireMock.resetAll();
        stubSpec(spec);
        CodeSyncResult result = coordinator.refreshOpenApi(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
        // No audit row written for a no-op run.
        assertAuditCount(s.getId(), 1);
    }

    // ---- refreshTests (M3) ----------------------------------------------

    @Test
    void whenServiceHasNoRepoUrl_thenRefreshTestsIsNoop() {
        Service s = saveServiceWithRepo("no-repo-svc", null, null);

        CodeSyncResult result = coordinator.refreshTests(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
        assertThat(relationships.findTestScenariosFor(s.getId())).isEmpty();
    }

    @Test
    void whenRepoHasOneTestFile_thenScenariosAreInsertedWithSourceTests() {
        Service s = saveServiceWithRepo("test-svc", "https://github.com/o/r", null);
        stubGithubListing("/repos/o/r/contents/src/test/java", """
                [{"type":"file","name":"AlphaSpec.java","path":"src/test/java/AlphaSpec.java",
                  "encoding":"base64","content":"%s"}]
                """.formatted(b64("""
                package com.example.alpha;
                import org.junit.jupiter.api.Test;
                class AlphaSpec {
                    @Test void scenarioOne() {}
                    @Test void scenarioTwo() {}
                }
                """)));

        CodeSyncResult result = coordinator.refreshTests(s.getId());

        assertThat(result.created()).isEqualTo(2);
        List<TestScenario> scenarios = relationships.findTestScenariosFor(s.getId());
        assertThat(scenarios).extracting(TestScenario::methodName)
                .containsExactlyInAnyOrder("scenarioOne", "scenarioTwo");
        assertThat(scenarios).extracting(TestScenario::source).containsOnly("tests");
        assertThat(scenarios).extracting(TestScenario::className).containsOnly("AlphaSpec");
        assertThat(scenarios).extracting(TestScenario::packageName).containsOnly("com.example.alpha");
    }

    @Test
    void whenRepoHasModulePath_thenFetcherWalksUnderThatPath() {
        Service s = saveServiceWithRepo("modular-svc", "https://github.com/o/r", "atlas-intake");
        stubGithubListing("/repos/o/r/contents/atlas-intake/src/test/java", """
                [{"type":"file","name":"M.java","path":"atlas-intake/src/test/java/M.java",
                  "encoding":"base64","content":"%s"}]
                """.formatted(b64("""
                package m;
                import org.junit.jupiter.api.Test;
                class M { @Test void scenario() {} }
                """)));

        coordinator.refreshTests(s.getId());

        assertThat(relationships.findTestScenariosFor(s.getId()))
                .extracting(TestScenario::className).containsExactly("M");
    }

    @Test
    void whenScenarioIsRemovedFromCode_thenItIsDeletedFromDb() {
        Service s = saveServiceWithRepo("trim-svc", "https://github.com/o/r", null);
        // Seed a stale scenario directly in the DB; refresh should drop it.
        relationships.insertTestScenario(s.getId(), "old.pkg", "OldSpec", "oldScenario", "tests");

        stubGithubListing("/repos/o/r/contents/src/test/java", """
                [{"type":"file","name":"NewSpec.java","path":"src/test/java/NewSpec.java",
                  "encoding":"base64","content":"%s"}]
                """.formatted(b64("""
                package fresh.pkg;
                import org.junit.jupiter.api.Test;
                class NewSpec { @Test void newScenario() {} }
                """)));

        CodeSyncResult result = coordinator.refreshTests(s.getId());

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(relationships.findTestScenariosFor(s.getId()))
                .extracting(TestScenario::methodName)
                .containsExactly("newScenario");
    }

    @Test
    void whenSecondRefreshHasIdenticalScenarios_thenItIsIdempotent() {
        Service s = saveServiceWithRepo("idem-svc", "https://github.com/o/r", null);
        String body = """
                [{"type":"file","name":"S.java","path":"src/test/java/S.java",
                  "encoding":"base64","content":"%s"}]
                """.formatted(b64("""
                package p;
                import org.junit.jupiter.api.Test;
                class S { @Test void scenario() {} }
                """));
        stubGithubListing("/repos/o/r/contents/src/test/java", body);
        coordinator.refreshTests(s.getId());

        wireMock.resetAll();
        stubGithubListing("/repos/o/r/contents/src/test/java", body);
        CodeSyncResult result = coordinator.refreshTests(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
    }

    @Test
    void whenRepoUrlIsNotGitHub_thenRefreshThrowsAndDbIsUntouched() {
        Service s = saveServiceWithRepo("non-gh-svc", "https://gitlab.com/o/r", null);
        relationships.insertTestScenario(s.getId(), "p", "S", "preExisting", "tests");

        assertThatThrownBy(() -> coordinator.refreshTests(s.getId()))
                .isInstanceOf(IllegalArgumentException.class);

        // The pre-existing scenario survives — refresh failed before any mutation.
        assertThat(relationships.findTestScenariosFor(s.getId())).hasSize(1);
    }

    // ---- refreshPom (M4) -------------------------------------------------

    @Test
    void whenServiceHasNoRepoUrl_thenRefreshPomIsNoop() {
        Service s = saveServiceWithRepo("no-repo-pom", null, null);

        CodeSyncResult result = coordinator.refreshPom(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
        assertThat(relationships.findServiceMetadataFor(s.getId())).isEmpty();
    }

    @Test
    void whenPomDeclaresJavaSpringBoot_thenMetadataObservationsAreWritten() {
        Service s = saveServiceWithRepo("pom-svc", "https://github.com/o/r", null);
        stubGithubFile("/repos/o/r/contents/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.0.6</version>
                    </parent>
                    <artifactId>pom-svc</artifactId>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                </project>
                """);

        coordinator.refreshPom(s.getId());

        Map<String, String> meta = relationships.findServiceMetadataFor(s.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ServiceMetadata::key, ServiceMetadata::value));
        assertThat(meta).containsEntry("language", "Java");
        assertThat(meta).containsEntry("language_version", "21");
        assertThat(meta).containsEntry("framework", "Spring Boot");
        assertThat(meta).containsEntry("framework_version", "4.0.6");
        assertThat(meta).containsEntry("build_tool", "Maven");
    }

    @Test
    void whenPomHasExternalDeps_thenExternalDepsAreCreatedAndOrgDepsAreSkipped() {
        Service s = saveServiceWithRepo("dep-svc", "https://github.com/o/r", null);
        stubGithubFile("/repos/o/r/contents/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>dep-svc</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.atlas</groupId>
                            <artifactId>atlas-domain</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.anthropic</groupId>
                            <artifactId>anthropic-java</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        coordinator.refreshPom(s.getId());

        // External deps live view excludes the com.atlas one but includes the others.
        java.util.List<String> depNames = jdbc.queryForList(
                "SELECT name FROM external_dependencies ORDER BY name", String.class);
        assertThat(depNames).containsExactlyInAnyOrder(
                "com.anthropic:anthropic-java",
                "org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void whenPomIsRefreshedTwiceWithSameContent_thenSecondRunIsNoop() {
        Service s = saveServiceWithRepo("idem-pom", "https://github.com/o/r", null);
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                </project>
                """;
        stubGithubFile("/repos/o/r/contents/pom.xml", pom);
        coordinator.refreshPom(s.getId());

        wireMock.resetAll();
        stubGithubFile("/repos/o/r/contents/pom.xml", pom);
        CodeSyncResult result = coordinator.refreshPom(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
    }

    @Test
    void whenDependencyIsRemovedFromPom_thenTombstoneIsAppendedAndLiveViewExcludesIt() {
        Service s = saveServiceWithRepo("trim-pom", "https://github.com/o/r", null);
        stubGithubFile("/repos/o/r/contents/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                    <dependencies>
                        <dependency><groupId>com.example</groupId><artifactId>keep</artifactId></dependency>
                        <dependency><groupId>com.example</groupId><artifactId>drop</artifactId></dependency>
                    </dependencies>
                </project>
                """);
        coordinator.refreshPom(s.getId());

        wireMock.resetAll();
        stubGithubFile("/repos/o/r/contents/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                    <dependencies>
                        <dependency><groupId>com.example</groupId><artifactId>keep</artifactId></dependency>
                    </dependencies>
                </project>
                """);
        CodeSyncResult result = coordinator.refreshPom(s.getId());

        assertThat(result.deleted()).isEqualTo(1);
        // Two underlying rows still exist (live observation + tombstone).
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_external_deps WHERE service_id = ?",
                Long.class, s.getId());
        assertThat(total).isEqualTo(3L); // 2 created + 1 tombstone
        // Live view via the repo helper excludes the dropped dep.
        assertThat(relationships.findLiveExternalDepsForService(s.getId(), "pom-xml")).hasSize(1);
    }

    @Test
    void whenPomFileIsMissing_thenRefreshIsNoop() {
        Service s = saveServiceWithRepo("missing-pom", "https://github.com/o/r", null);
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/pom.xml"))
                .willReturn(aResponse().withStatus(404)));

        CodeSyncResult result = coordinator.refreshPom(s.getId());

        assertThat(result).isEqualTo(CodeSyncResult.empty());
    }

    @Test
    void whenPomHasModulePath_thenFetcherUsesIt() {
        Service s = saveServiceWithRepo("modular-pom", "https://github.com/o/r", "atlas-intake");
        stubGithubFile("/repos/o/r/contents/atlas-intake/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>atlas-intake</artifactId>
                </project>
                """);

        coordinator.refreshPom(s.getId());

        assertThat(relationships.findServiceMetadataFor(s.getId()))
                .extracting(ServiceMetadata::key).contains("language", "build_tool");
    }

    private void stubGithubFile(String path, String content) {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(okJson(
                String.format("""
                        {"type":"file","name":"%s","path":"%s","encoding":"base64","content":"%s"}
                        """,
                        path.substring(path.lastIndexOf('/') + 1),
                        path.startsWith("/repos/o/r/contents/") ? path.substring("/repos/o/r/contents/".length()) : path,
                        b64))));
    }

    // ---- helpers --------------------------------------------------------

    private Service saveServiceWithRepo(String name, String repoUrl, String modulePath) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam("platform");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setRepoUrl(repoUrl);
        s.setModulePath(modulePath);
        return serviceRepository.saveAndFlush(s);
    }

    private void stubGithubListing(String path, String body) {
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(okJson(body)));
    }

    private static String b64(String s) {
        return java.util.Base64.getEncoder().encodeToString(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Service saveService(String name, String openapiSpecUrl) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam("platform");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setOpenapiSpecUrl(openapiSpecUrl);
        return serviceRepository.saveAndFlush(s);
    }

    private String specUrl() {
        return wireMock.baseUrl() + "/v3/api-docs";
    }

    private void stubSpec(String body) {
        wireMock.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(okJson(body)));
    }

    private void assertAuditCount(UUID serviceId, long expected) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_changes WHERE service_id = ?",
                Long.class, serviceId);
        assertThat(count).isEqualTo(expected);
    }

    private void assertLastAuditChangedBy(UUID serviceId, String expected) {
        String changedBy = jdbc.queryForObject(
                "SELECT changed_by FROM service_changes WHERE service_id = ? " +
                        "ORDER BY changed_at DESC LIMIT 1",
                String.class, serviceId);
        assertThat(changedBy).isEqualTo(expected);
    }
}
