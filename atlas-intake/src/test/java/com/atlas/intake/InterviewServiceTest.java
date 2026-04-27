package com.atlas.intake;

import com.atlas.anthropic.AnthropicGateway;
import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
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

    @MockitoBean
    AnthropicGateway anthropic;

    @Test
    void whenInterviewIsStarted_thenAsksForName() {
        InterviewService.TurnResult result = interviewService.next(null, null);

        assertThat(result.complete()).isFalse();
        assertThat(result.serviceId()).isNull();
        assertThat(result.question()).containsIgnoringCase("name");
        assertThat(result.state().stage()).isEqualTo(InterviewStage.AWAITING_NAME);
    }

    @Test
    void whenInterviewCapturesRequiredFieldsAndSkipsOptionals_thenServiceIsPersisted() {
        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "order-processor");
        // Description above the brief threshold avoids the clarification branch.
        r = interviewService.next(r.state(), "Receives checkout orders and forwards them to fulfillment.");
        r = interviewService.next(r.state(), "payments");
        r = interviewService.next(r.state(), "active");
        // 7 optional services-row fields, all skipped.
        for (int i = 0; i < 7; i++) {
            r = interviewService.next(r.state(), "skip");
        }

        assertThat(r.complete()).isTrue();
        assertThat(r.serviceId()).isNotNull();

        Optional<Service> persisted = repository.findById(r.serviceId());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getName()).isEqualTo("order-processor");
        assertThat(persisted.get().getOwnerTeam()).isEqualTo("payments");
        assertThat(persisted.get().getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(persisted.get().getDescription())
                .isEqualTo("Receives checkout orders and forwards them to fulfillment.");
        // Skipped optionals stay null.
        assertThat(persisted.get().getLanguage()).isNull();
        assertThat(persisted.get().getFramework()).isNull();
        assertThat(persisted.get().getRepoUrl()).isNull();
        assertThat(persisted.get().getDeployment()).isNull();
        assertThat(persisted.get().getSupportContact()).isNull();
        assertThat(persisted.get().getSla()).isNull();
        assertThat(persisted.get().getNotes()).isNull();
    }

    @Test
    void whenOptionalFieldsAreProvided_thenTheyArePersisted() {
        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "billing-api");
        r = interviewService.next(r.state(), "Processes invoices and emits payment events to downstream consumers.");
        r = interviewService.next(r.state(), "billing");
        r = interviewService.next(r.state(), "active");
        // language, framework, repo_url, deployment, support_contact, sla, notes
        r = interviewService.next(r.state(), "Java");
        r = interviewService.next(r.state(), "Spring Boot");
        r = interviewService.next(r.state(), "https://github.com/example/billing-api");
        r = interviewService.next(r.state(), "AWS ECS prod cluster");
        r = interviewService.next(r.state(), "billing-oncall@example.com");
        r = interviewService.next(r.state(), "99.9% monthly uptime");
        r = interviewService.next(r.state(), "Owns the invoice numbering sequence.");

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
        InterviewService.TurnResult r = interviewService.next(null, null);
        r = interviewService.next(r.state(), "metrics-svc");
        r = interviewService.next(r.state(), "Aggregates metrics from edge collectors and writes to Grafana.");
        r = interviewService.next(r.state(), "platform");
        r = interviewService.next(r.state(), "active");
        r = interviewService.next(r.state(), "Go");                  // language
        r = interviewService.next(r.state(), "skip");                // framework
        r = interviewService.next(r.state(), "");                    // repoUrl — empty also means skip
        r = interviewService.next(r.state(), "skip");                // deployment
        r = interviewService.next(r.state(), "platform-oncall");     // supportContact
        r = interviewService.next(r.state(), "skip");                // sla
        r = interviewService.next(r.state(), "skip");                // notes

        assertThat(r.complete()).isTrue();
        Service persisted = repository.findById(r.serviceId()).orElseThrow();
        assertThat(persisted.getLanguage()).isEqualTo("Go");
        assertThat(persisted.getFramework()).isNull();
        assertThat(persisted.getRepoUrl()).isNull();
        assertThat(persisted.getDeployment()).isNull();
        assertThat(persisted.getSupportContact()).isEqualTo("platform-oncall");
        assertThat(persisted.getSla()).isNull();
        assertThat(persisted.getNotes()).isNull();
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

        // After clarification answer, we should advance to ownerTeam and not re-clarify.
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
        // Anthropic should not be involved in pure validation reprompts.
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

        // After picking a different name, the interview moves on.
        r = interviewService.next(r.state(), "payments-svc-v2");
        assertThat(r.state().draft().name()).isEqualTo("payments-svc-v2");
        assertThat(r.state().stage()).isEqualTo(InterviewStage.AWAITING_DESCRIPTION);
    }
}
