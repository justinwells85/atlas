package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LandingPageRendererTest {

    private final LandingPageRenderer renderer = new LandingPageRenderer();

    @Test
    void whenServicesAreProvided_thenIndexTableContainsOneRowPerService() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Service a = svc("alpha-service", "Platform", "Handles A.", ServiceStatus.ACTIVE);
        Service b = svc("beta-service", "Apps", "Handles B.", ServiceStatus.IN_DEV);

        String rendered = renderer.render(
                List.of(a, b),
                Map.of(idA, "https://example/wiki/spaces/ATLAS/pages/100",
                        idB, "https://example/wiki/spaces/ATLAS/pages/101"),
                OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered)
                .contains("<h2>About this space</h2>")
                .contains("<h2>Service index</h2>")
                .contains("<table>")
                .contains("alpha-service")
                .contains("beta-service")
                .contains("Platform")
                .contains("Apps")
                .contains("active")
                .contains("in_dev")
                .contains("Handles A.")
                .contains("Handles B.")
                .contains("Last refreshed")
                .contains("source of truth");
    }

    @Test
    void whenNoServicesProvided_thenThinNoteAppearsInsteadOfTable() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("No services registered yet")
                .doesNotContain("<table>");
    }

    @Test
    void whenServiceHasPageUrl_thenServiceNameInTableIsHyperlinked() {
        UUID id = UUID.randomUUID();
        Service s = svcWithId(id, "linked-svc", "team", ServiceStatus.ACTIVE);

        String rendered = renderer.render(
                List.of(s),
                Map.of(id, "https://example/wiki/spaces/ATLAS/pages/200"),
                OffsetDateTime.now());

        assertThat(rendered).contains(
                "<a href=\"https://example/wiki/spaces/ATLAS/pages/200\">linked-svc</a>");
    }

    @Test
    void whenServiceHasNoPageUrl_thenServiceNameInTableIsPlainText() {
        UUID id = UUID.randomUUID();
        Service s = svcWithId(id, "unlinked-svc", "team", ServiceStatus.ACTIVE);

        String rendered = renderer.render(List.of(s), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("unlinked-svc")
                .doesNotContain("<a href=");
    }

    @Test
    void whenServicesAreOutOfOrder_thenIndexTableSortsAlphabetically() {
        Service zzz = svc("zzz-service", "team", null, ServiceStatus.ACTIVE);
        Service aaa = svc("aaa-service", "team", null, ServiceStatus.ACTIVE);

        String rendered = renderer.render(List.of(zzz, aaa), Map.of(), OffsetDateTime.now());

        int aaaPos = rendered.indexOf("aaa-service");
        int zzzPos = rendered.indexOf("zzz-service");
        assertThat(aaaPos).isPositive();
        assertThat(zzzPos).isPositive();
        assertThat(aaaPos).isLessThan(zzzPos);
    }

    private Service svc(String name, String owner, String description, ServiceStatus status) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(owner);
        s.setDescription(description);
        s.setStatus(status);
        return s;
    }

    /** Variant that lets the test pin a specific UUID (otherwise the field is null until JPA-saved). */
    private Service svcWithId(UUID id, String name, String owner, ServiceStatus status) {
        Service s = svc(name, owner, null, status);
        try {
            java.lang.reflect.Field idField = Service.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }
}
