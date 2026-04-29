package com.atlas.intake;

import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RemovalServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    RemovalService removalService;

    @Autowired
    ServiceRepository repository;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM service_changes");
        jdbc.update("DELETE FROM services");
    }

    @Test
    void whenRemovalIsStarted_thenAsksForServiceName() {
        RemovalService.RemovalResult r = removalService.next(null, null);

        assertThat(r.complete()).isFalse();
        assertThat(r.question()).containsIgnoringCase("name");
        assertThat(r.state().stage()).isEqualTo(RemovalStage.AWAITING_REMOVAL_NAME);
    }

    @Test
    void whenServiceExists_thenAsksForConfirmationShowingNameAndOwner() {
        save("billing-svc", "platform");

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "billing-svc");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(RemovalStage.AWAITING_REMOVAL_CONFIRM);
        assertThat(r.question()).contains("billing-svc").contains("platform");
    }

    @Test
    void whenUserChoosesRemoveAndConfirms_thenServiceIsSoftDeleted() {
        Service saved = save("legacy-billing", "platform");

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "legacy-billing");
        r = removalService.next(r.state(), "yes");

        assertThat(r.complete()).isTrue();
        assertThat(r.removedServiceId()).isEqualTo(saved.getId());
        // Hidden from JPA queries.
        assertThat(repository.findById(saved.getId())).isEmpty();
        // But row remains, with deleted_at set.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT deleted_at FROM services WHERE id = ?", saved.getId());
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    void whenUserChoosesRemoveAndConfirms_thenAuditRowIsWritten() {
        Service saved = save("audit-svc", "platform");

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "audit-svc");
        removalService.next(r.state(), "yes");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT change_type, changed_by FROM service_changes WHERE service_id = ?",
                saved.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("change_type")).isEqualTo("deleted");
        assertThat(rows.get(0).get("changed_by")).isEqualTo("intake-removal");
    }

    @Test
    void whenUserDeclinesAtConfirmation_thenServiceIsUntouched() {
        Service saved = save("safe-svc", "platform");

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "safe-svc");
        r = removalService.next(r.state(), "no");

        assertThat(r.complete()).isTrue();
        assertThat(r.removedServiceId()).isNull();
        // Service is still present and not soft-deleted.
        assertThat(repository.findById(saved.getId())).isPresent();
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_changes WHERE service_id = ?",
                Long.class, saved.getId());
        assertThat(auditCount).isEqualTo(0L);
    }

    @Test
    void whenServiceNameIsUnknown_thenStageRePromptsWithError() {
        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "ghost-svc");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(RemovalStage.AWAITING_REMOVAL_NAME);
        assertThat(r.question()).containsIgnoringCase("ghost-svc");
        assertThat(r.question()).containsIgnoringCase("not");
    }

    @Test
    void whenConfirmationInputIsAmbiguous_thenStageRePrompts() {
        save("ambig-svc", "platform");

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "ambig-svc");
        r = removalService.next(r.state(), "maybe");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(RemovalStage.AWAITING_REMOVAL_CONFIRM);
        assertThat(r.question()).containsIgnoringCase("yes");
    }

    @Test
    void whenServiceIsAlreadySoftDeleted_thenLookupFailsLikeUnknownName() {
        // A soft-deleted service should not be re-removable through intake;
        // the @SQLRestriction filter hides it from findByName.
        Service saved = save("retired-svc", "platform");
        repository.delete(saved);  // @SQLDelete soft-deletes

        RemovalService.RemovalResult r = removalService.next(null, null);
        r = removalService.next(r.state(), "retired-svc");

        assertThat(r.complete()).isFalse();
        assertThat(r.state().stage()).isEqualTo(RemovalStage.AWAITING_REMOVAL_NAME);
    }

    private Service save(String name, String ownerTeam) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(ServiceStatus.ACTIVE);
        return repository.saveAndFlush(s);
    }
}
