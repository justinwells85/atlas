package com.atlas.intake;

import com.atlas.anthropic.AnthropicGateway;
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
    AnthropicGateway anthropic;

    @BeforeEach
    void wipe() {
        // Tests share a Testcontainers Postgres across the class; clean any
        // services rows from previous methods so unique-name collisions and
        // FK-cascade leftovers don't leak between tests.
        repository.deleteAll();
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
        when(anthropic.complete(anyString())).thenReturn("What systems does it talk to?");

        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "metrics-agg");
        r = interviewService.next(r.state(), "stats");

        assertThat(r.complete()).isFalse();
        assertThat(r.question()).isEqualTo("What systems does it talk to?");
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_DESCRIPTION_CLARIFICATION);
        verify(anthropic).complete(contains("stats"));

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
        verify(anthropic, never()).complete(anyString());
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
        r = interviewService.next(r.state(), "Java");
        r = interviewService.next(r.state(), "Spring Boot");
        r = interviewService.next(r.state(), "https://github.com/example/billing-api");
        r = interviewService.next(r.state(), "AWS ECS prod cluster");
        r = interviewService.next(r.state(), "billing-oncall@example.com");
        r = interviewService.next(r.state(), "99.9% monthly uptime");
        r = interviewService.next(r.state(), "Owns the invoice numbering sequence.");
        r = skipAllSectionGates(r);

        assertThat(r.complete()).isTrue();
        Service persisted = repository.findById(r.serviceId()).orElseThrow();
        assertThat(persisted.getLanguage()).isEqualTo("Java");
        assertThat(persisted.getFramework()).isEqualTo("Spring Boot");
        assertThat(persisted.getRepoUrl()).isEqualTo("https://github.com/example/billing-api");
        assertThat(persisted.getDeployment()).isEqualTo("AWS ECS prod cluster");
        assertThat(persisted.getSupportContact()).isEqualTo("billing-oncall@example.com");
        assertThat(persisted.getSla()).isEqualTo("99.9% monthly uptime");
        assertThat(persisted.getNotes()).isEqualTo("Owns the invoice numbering sequence.");
    }

    @Test
    void whenSomeOptionalsAreProvidedAndOthersSkipped_thenOnlyProvidedOnesPersist() {
        InterviewService.TurnResult r = startWithRequiredFields("metrics-svc", "platform");
        r = interviewService.next(r.state(), "Go");                  // language
        r = interviewService.next(r.state(), "skip");                // framework
        r = interviewService.next(r.state(), "");                    // repoUrl — empty also skips
        r = interviewService.next(r.state(), "skip");                // deployment
        r = interviewService.next(r.state(), "platform-oncall");     // supportContact
        r = interviewService.next(r.state(), "skip");                // sla
        r = interviewService.next(r.state(), "skip");                // notes
        r = skipAllSectionGates(r);

        assertThat(r.complete()).isTrue();
        Service persisted = repository.findById(r.serviceId()).orElseThrow();
        assertThat(persisted.getLanguage()).isEqualTo("Go");
        assertThat(persisted.getSupportContact()).isEqualTo("platform-oncall");
        assertThat(persisted.getFramework()).isNull();
        assertThat(persisted.getRepoUrl()).isNull();
    }

    // -----------------------------------------------------------------------
    // APIs section (Phase 3.5 M2)
    // -----------------------------------------------------------------------

    @Test
    void whenSingleApiIsCaptured_thenItPersists() {
        InterviewService.TurnResult r = startWithRequiredFields("orders-api", "orders");
        r = skipAllOptionalServicesRowFields(r);
        // APIs section
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "/v1/orders");
        r = interviewService.next(r.state(), "POST");
        r = interviewService.next(r.state(), "api-key");
        r = interviewService.next(r.state(), "Place an order");
        r = interviewService.next(r.state(), "no");                 // another?
        // Skip the two dependency sections.
        r = interviewService.next(r.state(), "skip");
        r = interviewService.next(r.state(), "skip");

        assertThat(r.complete()).isTrue();
        var apis = relationships.findApisFor(r.serviceId());
        assertThat(apis).hasSize(1);
        assertThat(apis.get(0).path()).isEqualTo("/v1/orders");
        assertThat(apis.get(0).method()).isEqualTo("POST");
        assertThat(apis.get(0).authMethod()).isEqualTo("api-key");
        assertThat(apis.get(0).description()).isEqualTo("Place an order");
    }

    @Test
    void whenMultipleApisAreCaptured_thenAllPersist() {
        InterviewService.TurnResult r = startWithRequiredFields("inventory-api", "inventory");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "yes");
        // First API
        r = interviewService.next(r.state(), "/v1/items");
        r = interviewService.next(r.state(), "GET");
        r = interviewService.next(r.state(), "skip");                // auth optional
        r = interviewService.next(r.state(), "List inventory items");
        r = interviewService.next(r.state(), "yes");                 // another?
        // Second API
        r = interviewService.next(r.state(), "/v1/items/{id}");
        r = interviewService.next(r.state(), "GET");
        r = interviewService.next(r.state(), "");                    // auth empty also skips
        r = interviewService.next(r.state(), "skip");                // description optional
        r = interviewService.next(r.state(), "no");                  // another?
        r = skipAllDependencySections(r);

        assertThat(r.complete()).isTrue();
        var apis = relationships.findApisFor(r.serviceId());
        assertThat(apis).hasSize(2);
        // findApisFor orders by (path, method).
        assertThat(apis.get(0).path()).isEqualTo("/v1/items");
        assertThat(apis.get(0).description()).isEqualTo("List inventory items");
        assertThat(apis.get(0).authMethod()).isNull();
        assertThat(apis.get(1).path()).isEqualTo("/v1/items/{id}");
        assertThat(apis.get(1).description()).isNull();
    }

    @Test
    void whenInvalidHttpMethodIsProvided_thenInterviewReprompts() {
        InterviewService.TurnResult r = startWithRequiredFields("widgets-api", "widgets");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "yes");
        r = interviewService.next(r.state(), "/v1/widgets");
        r = interviewService.next(r.state(), "BANANA");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_API_METHOD);
        assertThat(r.question()).containsIgnoringCase("method must be one of");

        // After a valid method, the interview moves on.
        r = interviewService.next(r.state(), "POST");
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_API_AUTH);
    }

    // -----------------------------------------------------------------------
    // Service-to-service dependencies (Phase 3.5 M2)
    // -----------------------------------------------------------------------

    @Test
    void whenUpstreamReferencesUnknownService_thenInterviewReprompts() {
        InterviewService.TurnResult r = startWithRequiredFields("checkout-svc", "checkout");
        r = skipAllOptionalServicesRowFields(r);
        r = interviewService.next(r.state(), "skip");                // APIs
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
        r = interviewService.next(r.state(), "skip");                // APIs
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
        r = interviewService.next(r.state(), "skip");                // APIs
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
        r = interviewService.next(r.state(), "skip");                // APIs
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
        for (int i = 0; i < 7; i++) r = interviewService.next(r.state(), "skip");
        return r;
    }

    private InterviewService.TurnResult skipAllSectionGates(InterviewService.TurnResult r) {
        for (int i = 0; i < 3; i++) r = interviewService.next(r.state(), "skip");
        return r;
    }

    private InterviewService.TurnResult skipAllDependencySections(InterviewService.TurnResult r) {
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
