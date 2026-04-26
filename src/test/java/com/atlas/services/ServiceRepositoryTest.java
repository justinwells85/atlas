package com.atlas.services;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ServiceRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    ServiceRepository repository;

    @Test
    void whenServiceIsPersisted_thenItCanBeLoadedById() {
        Service svc = new Service();
        svc.setName("payments-api");
        svc.setDescription("handles checkout payments");
        svc.setOwnerTeam("payments");
        svc.setStatus(ServiceStatus.ACTIVE);

        Service saved = repository.save(svc);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        Optional<Service> loaded = repository.findById(id);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("payments-api");
        assertThat(loaded.get().getOwnerTeam()).isEqualTo("payments");
        assertThat(loaded.get().getStatus()).isEqualTo(ServiceStatus.ACTIVE);
    }

    @Test
    void whenServiceIsUpdated_thenUpdatedAtMovesAheadOfCreatedAt() throws InterruptedException {
        Service svc = new Service();
        svc.setName("inventory-svc");
        Service saved = repository.saveAndFlush(svc);
        UUID id = saved.getId();

        // Sleep so the post-update timestamp is unambiguously after the create timestamp.
        Thread.sleep(5);

        Service toUpdate = repository.findById(id).orElseThrow();
        toUpdate.setDescription("now has a description");
        Service updated = repository.saveAndFlush(toUpdate);

        assertThat(updated.getCreatedAt()).isNotNull();
        assertThat(updated.getUpdatedAt()).isNotNull();
        assertThat(updated.getUpdatedAt()).isAfter(updated.getCreatedAt());
    }

    @Test
    void whenMetadataMapIsPersisted_thenItRoundTripsThroughJson() {
        Service svc = new Service();
        svc.setName("metadata-roundtrip");
        svc.setMetadata(Map.of(
                "data_classification", "internal",
                "tier", "gold",
                "tags", java.util.List.of("payments", "core")
        ));

        Service saved = repository.saveAndFlush(svc);
        UUID id = saved.getId();
        // Detach so the next load comes from the DB, not the persistence-context cache.
        repository.flush();

        Service loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getMetadata())
                .containsEntry("data_classification", "internal")
                .containsEntry("tier", "gold");
        assertThat(loaded.getMetadata().get("tags"))
                .isEqualTo(java.util.List.of("payments", "core"));
    }

    @Test
    void whenServiceStatusIsLoaded_thenItIsParsedAsJavaEnum() {
        Service svc = new Service();
        svc.setName("status-roundtrip");
        svc.setStatus(ServiceStatus.IN_DEV);

        UUID id = repository.saveAndFlush(svc).getId();

        Service loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ServiceStatus.IN_DEV);
    }
}
