package com.atlas.intake;

import com.atlas.llm.LlmGateway;
import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class InterviewServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    InterviewService interviewService;

    @Autowired
    ServiceRepository repository;

    @Autowired
    ServiceRelationshipsRepository relationships;

    @MockitoBean
    LlmGateway llm;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        // Tests share a Testcontainers Postgres across the class; clean any
        // rows from previous methods so unique-name collisions and FK-cascade
        // leftovers don't leak between tests. Order matters: link rows first,
        // then leaf entities, then services (FK cascade catches the rest).
        // service_changes has a soft FK on service_id (no CASCADE) — clean it
        // explicitly so the audit-row test sees a clean slate.
        jdbc.update("DELETE FROM service_external_deps");
        jdbc.update("DELETE FROM service_databases");
        jdbc.update("DELETE FROM external_dependencies");
        jdbc.update("DELETE FROM data_stores");
        jdbc.update("DELETE FROM service_changes");
        // Native hard-DELETE — repository.deleteAll() would now soft-delete
        // (V11 / ADR-014), leaving rows that violate name UNIQUE on the next
        // INSERT. Tests need a true clean slate between methods.
        jdbc.update("DELETE FROM services");
    }

    // -----------------------------------------------------------------------
    // Required-field flow (Phase 2)
    // -----------------------------------------------------------------------

    @Test
    void whenInterviewIsStarted_thenAsksForName() {
        InterviewService.TurnResult result = interviewService.next(null, null);

        assertThat(result.complete()).isFalse();
        assertThat(result.serviceId()).isNull();
        assertThat(result.question()).containsIgnoringCase("name");
        assertThat(result.state().stage()).isEqualTo(InterviewStage.AWAITING_NAME);
    }

    @Test
    void whenDescriptionIsBrief_thenFollowUpClarificationIsAsked() {
        when(llm.complete(anyString())).thenReturn("What systems does it talk to?");

        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "metrics-agg");
        r = interviewService.next(r.state(), "stats");

        assertThat(r.complete()).isFalse();
        assertThat(r.question()).isEqualTo("What systems does it talk to?");
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_DESCRIPTION_CLARIFICATION);
        verify(llm).complete(contains("stats"));

        r = interviewService.next(r.state(), "Reads from kafka topic 'orders' and pushes to grafana.");
        assertThat(r.state().descriptionClarified()).isTrue();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_OWNER_TEAM);
        assertThat(r.question()).containsIgnoringCase("team");
    }

    @Test
    void whenRequiredFieldIsBlank_thenInterviewReprompts() {
        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "   ");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_NAME);
        assertThat(r.question()).containsIgnoringCase("blank");
        assertThat(r.question()).containsIgnoringCase("name");
        verify(llm, never()).complete(anyString());
    }

    @Test
    void whenServiceNameAlreadyExists_thenInterviewSurfacesTheConflict() {
        Service existing = new Service();
        existing.setName("payments-svc");
        existing.setStatus(ServiceStatus.ACTIVE);
        repository.saveAndFlush(existing);

        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "payments-svc");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_NAME);
        assertThat(r.question()).contains("already exists");
        assertThat(r.state().draft().name()).isNull();

        r = interviewService.next(r.state(), "payments-svc-v2");
        assertThat(r.state().draft().name()).isEqualTo("payments-svc-v2");
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_DESCRIPTION);
    }

    // -----------------------------------------------------------------------
    // Reactivate flow (ADR-014 / DD-013 caveat)
    // -----------------------------------------------------------------------

    @Test
    void whenNameMatchesSoftDeletedRow_thenInterviewReactivatesItKeepingTheSameUuid() {
        // Arrange: a service that was created, then soft-deleted.
        Service original = new Service();
        original.setName("revival-service");
        original.setOwnerTeam("old-team");
        original.setStatus(ServiceStatus.ACTIVE);
        original.setDescription("Original description");
        Service saved = repository.saveAndFlush(original);
        UUID originalId = saved.getId();
        // Soft-delete via repository.delete() — @SQLDelete rewrites to UPDATE.
        repository.delete(saved);
        // Confirm it's invisible to JPA.
        assertThat(repository.findByName("revival-service")).isEmpty();
        assertThat(repository.findByNameIncludingDeleted("revival-service"))
                .isPresent()
                .get().extracting(Service::getDeletedAt).isNotNull();

        // Act: run intake with the same name; it should not error at the
        // name stage and should reactivate at persist time.
        InterviewService.TurnResult r = startWithRequiredFields("revival-service", "new-team");
        r = skipAllOptionalsAndSections(r);

        // Assert: persisted, same UUID, deleted_at cleared, fields updated.
        assertThat(r.complete()).isTrue();
        assertThat(r.serviceId()).isEqualTo(originalId);

        Optional<Service> persisted = repository.findById(originalId);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getDeletedAt()).isNull();
        assertThat(persisted.get().getOwnerTeam()).isEqualTo("new-team");
        assertThat(persisted.get().getConfluencePageId()).isNull();

        // Audit row written with change_type = 'reactivated'.
        Long reactivatedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_changes " +
                        "WHERE service_id = ? AND change_type = 'reactivated'",
                Long.class, originalId);
        assertThat(reactivatedCount).isEqualTo(1L);
    }

    @Test
    void whenNameMatchesActiveRow_thenInterviewStillSurfacesConflict() {
        // The reactivate path must NOT engage when the existing row is active.
        Service active = new Service();
        active.setName("alive-service");
        active.setStatus(ServiceStatus.ACTIVE);
        repository.saveAndFlush(active);

        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "alive-service");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_NAME);
        assertThat(r.question()).contains("already exists");
    }

    // -----------------------------------------------------------------------
    // Optional services-row fields (Phase 3.5 M1)
    // -----------------------------------------------------------------------

    @Test
    void whenInterviewCapturesRequiredFieldsAndSkipsEverythingOptional_thenServiceIsPersisted() {
        InterviewService.TurnResult r = startWithRequiredFields("order-processor", "payments");
        r = skipAllOptionalsAndSections(r);

        assertThat(r.complete()).isTrue();
        assertThat(r.serviceId()).isNotNull();

        Optional<Service> persisted = repository.findById(r.serviceId());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getName()).isEqualTo("order-processor");
        assertThat(persisted.get().getOwnerTeam()).isEqualTo("payments");
        assertThat(persisted.get().getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(persisted.get().getLanguage()).isNull();
        assertThat(relationships.findApisFor(r.serviceId())).isEmpty();
        assertThat(relationships.findUpstreamDependenciesOf(r.serviceId())).isEmpty();
        assertThat(relationships.findDownstreamDependenciesOf(r.serviceId())).isEmpty();
    }

    @Test
    void whenOptionalFieldsAreProvided_thenTheyArePersisted() {
        InterviewService.TurnResult r = startWithRequiredFields("billing-api", "billing");
        r = interviewService.next(r.state(), "https://github.com/example/billing-api");      // repo_url
        r = interviewService.next(r.state(), "https://billing.local/v3/api-docs");           // openapi_spec_url
        r = interviewService.next(r.state(), "billing-svc");                                  // module_path
        r = interviewService.next(r.state(), "AWS ECS prod cluster");                        // deployment
        r = interviewService.next(r.state(), "billing-oncall@example.com");                  // support_contact
        r = interviewService.next(r.state(), "99.9% monthly uptime");                        // sla
        r = interviewService.next(r.state(), "Owns the invoice numbering sequence.");        // notes
        r = skipAllSectionGates(r);

        assertThat(r.complete()).isTrue();
        Service persisted = repository.findById(r.serviceId()).orElseThrow();
        assertThat(persisted.getRepoUrl()).isEqualTo("https://github.com/example/billing-api");
        assertThat(persisted.getOpenapiSpecUrl()).isEqualTo("https://billing.local/v3/api-docs");
        assertThat(persisted.getModulePath()).isEqualTo("billing-svc");
        assertThat(persisted.getDeployment()).isEqualTo("AWS ECS prod cluster");
        assertThat(persisted.getSupportContact()).isEqualTo("billing-oncall@example.com");
        assertThat(persisted.getSla()).isEqualTo("99.9% monthly uptime");
        assertThat(persisted.getNotes()).isEqualTo("Owns the invoice numbering sequence.");
        // Language/framework not asked anymore — null until pom-source code-sync fills service_metadata.
        assertThat(persisted.getLanguage()).isNull();
        assertThat(persisted.getFramework()).isNull();
    }

    @Test
    void whenSomeOptionalsAreProvidedAndOthersSkipped_thenOnlyProvidedOnesPersist() {
        InterviewService.TurnResult r = startWithRequiredFields("metrics-svc", "platform");
        r = interviewService.next(r.state(), "https://github.com/example/metrics-svc"); // repo_url
        r = interviewService.next(r.state(), "skip");                                   // openapi_spec_url
        r = interviewService.next(r.state(), "");                                       // module_path — empty skips
        r = interviewService.next(r.state(), "skip");                                   // deployment
        r = interviewService.next(r.state(), "platform-oncall");                        // support_contact
        r = interviewService.next(r.state(), "skip");                                   // sla
        r = interviewService.next(r.state(), "skip");                                   // notes
        r = skipAllSectionGates(r);

        assertThat(r.complete()).isTrue();
        Service persisted = repository.findById(r.serviceId()).orElseThrow();
        assertThat(persisted.getRepoUrl()).isEqualTo("https://github.com/example/metrics-svc");
        assertThat(persisted.getOpenapiSpecUrl()).isNull();
        assertThat(persisted.getModulePath()).isNull();
        assertThat(persisted.getSupportContact()).isEqualTo("platform-oncall");
        assertThat(persisted.getSla()).isNull();
    }

    // -----------------------------------------------------------------------
    // APIs section — REMOVED in M5.
    //
    // The interview no longer prompts for APIs; OpenAPI ingestion (M1+)
    // populates apis rows from services.openapi_spec_url. The 9 tests that
    // used to exercise the AWAITING_API_* stages were dropped at M5 — the
    // CodeSyncCoordinatorTest covers the new derivation path. Stages remain
    // in the InterviewStage enum + applyInput dispatch for backward
    // compatibility with serialized state.
    // -----------------------------------------------------------------------

    @Test
    void whenInterviewIsAtSectionGates_thenApisGateIsNotAsked() {
        // The first section gate after the optional services-row fields is
        // upstream-dependencies, NOT APIs (M5: APIs auto-derived).
        InterviewService.TurnResult r = startWithRequiredFields("post-m5-svc", "team");
        r = skipAllOptionalServicesRowFields(r);

        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_HAS_UPSTREAM_DEPS);
        assertThat(r.question()).contains("depend on any other services");
    }

    @Test
    void whenInterviewWalksOptionalFields_thenLanguageAndFrameworkAreNotAsked() {
        // The first optional-field prompt after STATUS is REPO_URL, not LANGUAGE
        // (M5: language/framework auto-derived from pom.xml).
        InterviewService.TurnResult r = startWithRequiredFields("no-lang-prompt", "team");

        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_REPO_URL);
        assertThat(r.question()).containsIgnoringCase("source repository URL");
    }

    // -----------------------------------------------------------------------
    // service_changes audit (Phase 3.6)
    // -----------------------------------------------------------------------

    @Test
    void whenInterviewCompletes_thenAuditRowIsWritten() {
        InterviewService.TurnResult r = startWithRequiredFields("audit-svc", "platform");
        r = skipAllOptionalsAndSections(r);

        assertThat(r.complete()).isTrue();
        java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT change_type, changed_by, summary FROM service_changes WHERE service_id = ?",
                r.serviceId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("change_type")).isEqualTo("created");
        assertThat(rows.get(0).get("changed_by")).isEqualTo("intake-agent");
        assertThat((String) rows.get(0).get("summary")).contains("intake interview");
    }

    // -----------------------------------------------------------------------
    // Service-to-service dependencies (Phase 3.5 M2)
    // -----------------------------------------------------------------------

    @Test
    void whenUpstreamReferencesUnknownService_thenInterviewReprompts() {
        InterviewService.TurnResult r = startWithRequiredFields("checkout-svc", "checkout");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "yes");                 // upstream gate
        r = interviewService.next(r.state(), "no-such-service");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_UPSTREAM_NAME);
        assertThat(r.question()).contains("No service named 'no-such-service'");
    }

    @Test
    void whenUpstreamReferencesSelf_thenInterviewReprompts() {
        InterviewService.TurnResult r = startWithRequiredFields("loop-svc", "loops");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "yes");                 // upstream gate
        r = interviewService.next(r.state(), "loop-svc");            // self

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_UPSTREAM_NAME);
        assertThat(r.question()).containsIgnoringCase("can't depend on itself");
    }

    @Test
    void whenUpstreamAndDownstreamCapture_thenDirectedEdgesPersist() {
        // Two existing services for "this" service to reference.
        Service inventory = saveSimpleService("inventory-api", "inventory");
        Service shipping  = saveSimpleService("shipping-api", "logistics");

        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        // Upstream: orders depends on inventory
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "inventory-api");
        r = interviewService.next(r.state(), "checks stock before placing");
        r = interviewService.next(r.state(), "no");                  // another?
        // Downstream: shipping depends on orders
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "shipping-api");
        r = interviewService.next(r.state(), "consumes order events");
        r = interviewService.next(r.state(), "no");                  // another?
        r = skipStorageSections(r);

        assertThat(r.complete()).isTrue();
        var upstream = relationships.findUpstreamDependenciesOf(r.serviceId());
        assertThat(upstream).hasSize(1);
        assertThat(upstream.get(0).upstreamServiceName()).isEqualTo("inventory-api");
        assertThat(upstream.get(0).upstreamServiceId()).isEqualTo(inventory.getId());
        assertThat(upstream.get(0).description()).isEqualTo("checks stock before placing");

        var downstream = relationships.findDownstreamDependenciesOf(r.serviceId());
        assertThat(downstream).hasSize(1);
        assertThat(downstream.get(0).downstreamServiceName()).isEqualTo("shipping-api");
        assertThat(downstream.get(0).downstreamServiceId()).isEqualTo(shipping.getId());
        assertThat(downstream.get(0).description()).isEqualTo("consumes order events");
    }

    @Test
    void whenSameUpstreamServiceIsAddedTwice_thenInterviewReprompts() {
        saveSimpleService("inventory-api", "inventory");

        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "yes");                 // upstream gate
        r = interviewService.next(r.state(), "inventory-api");
        r = interviewService.next(r.state(), "first edge description");
        r = interviewService.next(r.state(), "yes");                 // another upstream?
        r = interviewService.next(r.state(), "inventory-api");       // same again

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_UPSTREAM_NAME);
        assertThat(r.question()).contains("already in this list");
    }

    // -----------------------------------------------------------------------
    // Databases section (Phase 3.5 M3) — lookup-or-create
    // -----------------------------------------------------------------------

    @Test
    void whenNewDatabaseIsCaptured_thenBothDatabaseAndLinkRowsAreCreated() {
        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // upstream
        r = interviewService.next(r.state(), "skip");                // downstream
        // Databases: new entity
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "orders-db");
        r = interviewService.next(r.state(), "postgres");            // engine for new
        r = interviewService.next(r.state(), "yes");                 // is_owner
        r = interviewService.next(r.state(), "primary store for orders");
        r = interviewService.next(r.state(), "no");                  // another?
        r = interviewService.next(r.state(), "skip");                // external deps

        assertThat(r.complete()).isTrue();
        var dbs = relationships.findDatabasesFor(r.serviceId());
        assertThat(dbs).hasSize(1);
        assertThat(dbs.get(0).databaseName()).isEqualTo("orders-db");
        assertThat(dbs.get(0).engine()).isEqualTo("postgres");
        assertThat(dbs.get(0).isOwner()).isTrue();
        assertThat(dbs.get(0).description()).isEqualTo("primary store for orders");
    }

    @Test
    void whenExistingDatabaseIsReferenced_thenOnlyLinkRowIsCreated() {
        // Pre-existing database row.
        java.util.UUID existingDbId = relationships.insertDatabase("shared-cache", "redis");

        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // upstream
        r = interviewService.next(r.state(), "skip");                // downstream
        r = interviewService.next(r.state(), "yes");                 // databases gate
        r = interviewService.next(r.state(), "shared-cache");        // existing → engine skipped
        // Note: no engine prompt because the database already exists.
        r = interviewService.next(r.state(), "no");                  // is_owner
        r = interviewService.next(r.state(), "session cache");
        r = interviewService.next(r.state(), "no");                  // another?
        r = interviewService.next(r.state(), "skip");                // external deps

        assertThat(r.complete()).isTrue();
        var dbs = relationships.findDatabasesFor(r.serviceId());
        assertThat(dbs).hasSize(1);
        assertThat(dbs.get(0).databaseId()).isEqualTo(existingDbId);
        assertThat(dbs.get(0).databaseName()).isEqualTo("shared-cache");
        assertThat(dbs.get(0).engine()).isEqualTo("redis");          // unchanged from creation
        assertThat(dbs.get(0).isOwner()).isFalse();
        // No new database row created — count stays 1.
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM data_stores", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void whenSameDatabaseIsAddedTwice_thenInterviewReprompts() {
        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // upstream
        r = interviewService.next(r.state(), "skip");                // downstream
        r = interviewService.next(r.state(), "yes");                 // databases gate
        r = interviewService.next(r.state(), "orders-db");
        r = interviewService.next(r.state(), "postgres");
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "primary");
        r = interviewService.next(r.state(), "yes");                 // another db?
        r = interviewService.next(r.state(), "orders-db");           // same again

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_DATABASE_NAME);
        assertThat(r.question()).contains("already in this list");
    }

    // -----------------------------------------------------------------------
    // External dependencies section (Phase 3.5 M3) — lookup-or-create
    // -----------------------------------------------------------------------

    @Test
    void whenNewExternalDependencyIsCaptured_thenBothEntityAndLinkRowsAreCreated() {
        InterviewService.TurnResult r = startWithRequiredFields("payments-api", "payments");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // upstream
        r = interviewService.next(r.state(), "skip");                // downstream
        r = interviewService.next(r.state(), "skip");                // databases
        // External deps: new entity
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "Stripe");
        r = interviewService.next(r.state(), "https://stripe.com");  // url for new
        r = interviewService.next(r.state(), "card payment processing");
        r = interviewService.next(r.state(), "no");                  // another?

        assertThat(r.complete()).isTrue();
        var deps = relationships.findExternalDependenciesFor(r.serviceId());
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("Stripe");
        assertThat(deps.get(0).url()).isEqualTo("https://stripe.com");
        assertThat(deps.get(0).description()).isEqualTo("card payment processing");
    }

    @Test
    void whenExistingExternalDependencyIsReferenced_thenOnlyLinkRowIsCreated() {
        java.util.UUID existingId = relationships.insertExternalDependency("SendGrid", "https://sendgrid.com");

        InterviewService.TurnResult r = startWithRequiredFields("notifications-svc", "platform");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // upstream
        r = interviewService.next(r.state(), "skip");                // downstream
        r = interviewService.next(r.state(), "skip");                // databases
        r = interviewService.next(r.state(), "yes");                 // external deps gate
        r = interviewService.next(r.state(), "SendGrid");            // existing → URL skipped
        r = interviewService.next(r.state(), "transactional email");
        r = interviewService.next(r.state(), "no");                  // another?

        assertThat(r.complete()).isTrue();
        var deps = relationships.findExternalDependenciesFor(r.serviceId());
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).externalDependencyId()).isEqualTo(existingId);
        assertThat(deps.get(0).name()).isEqualTo("SendGrid");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM external_dependencies", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private InterviewService.TurnResult startWithRequiredFields(String name, String team) {
        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), name);
        r = interviewService.next(r.state(), "Receives requests and forwards them to fulfillment partners.");
        r = interviewService.next(r.state(), team);
        r = interviewService.next(r.state(), "active");
        return r;
    }

    private InterviewService.TurnResult skipAllOptionalServicesRowFields(InterviewService.TurnResult r) {
        // 7 optional services-row prompts: REPO_URL, OPENAPI_SPEC_URL, MODULE_PATH,
        // DEPLOYMENT, SUPPORT_CONTACT, SLA, NOTES (M5 dropped LANGUAGE/FRAMEWORK).
        for (int i = 0; i < 7; i++) r = interviewService.next(r.state(), "skip");
        return r;
    }

    private InterviewService.TurnResult skipAllSectionGates(InterviewService.TurnResult r) {
        // 4 section gates: upstream deps, downstream deps, databases, external deps
        // (M5 dropped the APIs gate — auto-derived from openapi).
        for (int i = 0; i < 4; i++) r = interviewService.next(r.state(), "skip");
        return r;
    }

    private InterviewService.TurnResult skipStorageSections(InterviewService.TurnResult r) {
        // 2 sections after dependencies: databases, external deps
        for (int i = 0; i < 2; i++) r = interviewService.next(r.state(), "skip");
        return r;
    }

    private InterviewService.TurnResult skipAllOptionalsAndSections(InterviewService.TurnResult r) {
        r = skipAllOptionalServicesRowFields(r);
        r = skipAllSectionGates(r);
        return r;
    }

    private Service saveSimpleService(String name, String ownerTeam) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(ServiceStatus.ACTIVE);
        return repository.saveAndFlush(s);
    }
}
