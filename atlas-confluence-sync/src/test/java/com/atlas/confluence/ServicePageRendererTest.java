package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServicePageRendererTest {

    private final ServicePageRenderer renderer = new ServicePageRenderer();

    @Test
    void whenServiceHasFullData_thenAllSevenSectionHeadersAreRendered() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("<h2>Overview</h2>")
                .contains("<h2>Technical Details</h2>")
                .contains("<h2>APIs</h2>")
                .contains("<h2>Dependencies</h2>")
                .contains("<h2>Data</h2>")
                .contains("<h2>Operational</h2>")
                .contains("<h2>Change History</h2>");
    }

    @Test
    void whenServiceHasFullData_thenOverviewIncludesDescriptionOwnerAndStatus() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("Handles billing for active subscriptions.")
                .contains("Platform team")
                .contains("active");
    }

    @Test
    void whenServiceHasFullData_thenTechnicalDetailsIncludeLanguageFrameworkRepoLinkAndDeployment() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("Java 21")
                .contains("Spring Boot 4")
                .contains("AWS ECS")
                .contains("href=\"https://github.com/example/billing-service\"");
    }

    @Test
    void whenServiceHasFullData_thenApisSectionListsMethodPathAndConsumers() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("GET")
                .contains("/v1/invoices")
                .contains("Invoice list endpoint")
                .contains("checkout-service");
    }

    @Test
    void whenServiceHasFullData_thenDependenciesSectionListsUpstreamDownstreamDatabasesAndExternalDeps() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("auth-service")
                .contains("checkout-service")
                .contains("billing-db")
                .contains("Stripe");
    }

    @Test
    void whenServiceHasFullData_thenDataSectionListsOwnedDatabasesAndDataClassification() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("billing-db")
                .contains("PII");
    }

    @Test
    void whenServiceHasFullData_thenOperationalSectionIncludesSupportContactSlaAndNotes() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("billing-oncall@example.com")
                .contains("99.9% uptime")
                .contains("Migrating off legacy charge codes Q3.");
    }

    @Test
    void whenServiceHasRecentChanges_thenChangeHistoryListsThem() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("intake-agent")
                .contains("update")
                .contains("name, owner_team");
    }

    @Test
    void whenServiceHasMinimalData_thenAllSevenSectionsStillRenderWithThinNotes() {
        String rendered = renderer.render(minimalContext());

        // Still has all 7 section headers
        assertThat(rendered)
                .contains("<h2>Overview</h2>")
                .contains("<h2>Technical Details</h2>")
                .contains("<h2>APIs</h2>")
                .contains("<h2>Dependencies</h2>")
                .contains("<h2>Data</h2>")
                .contains("<h2>Operational</h2>")
                .contains("<h2>Change History</h2>");

        // Thin notes for missing relationship data
        assertThat(rendered)
                .contains("No APIs documented")
                .contains("No upstream services documented")
                .contains("No downstream services documented")
                .contains("No databases documented")
                .contains("No external dependencies documented");
    }

    @Test
    void whenServiceHasMinimalData_thenRendererDoesNotThrowOnNullFields() {
        // Should not throw — null fields are handled gracefully.
        String rendered = renderer.render(minimalContext());

        assertThat(rendered).isNotEmpty();
    }

    @Test
    void whenDescriptionContainsXmlSpecialChars_thenTheyAreEscapedInOutput() {
        Service s = new Service();
        s.setName("xss-test");
        s.setDescription("<script>alert('x')</script> & friends");
        s.setStatus(ServiceStatus.ACTIVE);
        ServicePageContext ctx = emptyContextFor(s);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("&amp;");
    }

    @Test
    void whenServiceHasNoApiConsumers_thenApiRendersWithoutConsumerLine() {
        Service s = baseService();
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", "none", "Health check");
        ServicePageContext ctx = new ServicePageContext(
                s,
                List.of(new ApiPresentation(api, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("/v1/health")
                .contains("Health check")
                .doesNotContain("Consumers:");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private ServicePageContext fullContext() {
        Service s = new Service();
        s.setName("billing-service");
        s.setDescription("Handles billing for active subscriptions.");
        s.setOwnerTeam("Platform team");
        s.setStatus(ServiceStatus.ACTIVE);
        s.setLanguage("Java 21");
        s.setFramework("Spring Boot 4");
        s.setRepoUrl("https://github.com/example/billing-service");
        s.setDeployment("AWS ECS");
        s.setSupportContact("billing-oncall@example.com");
        s.setSla("99.9% uptime");
        s.setNotes("Migrating off legacy charge codes Q3.");
        Map<String, Object> meta = new HashMap<>();
        meta.put("data_classification", "PII");
        s.setMetadata(meta);

        ApiSummary api1 = new ApiSummary(UUID.randomUUID(), "/v1/invoices", "GET", "bearer", "Invoice list endpoint");
        ApiConsumer consumer = new ApiConsumer(UUID.randomUUID(), "checkout-service", "Reads invoice totals");
        ApiPresentation api1Pres = new ApiPresentation(api1, List.of(consumer));

        ServiceDependencyEdge upstream = new ServiceDependencyEdge(
                UUID.randomUUID(), "auth-service",
                UUID.randomUUID(), "billing-service",
                "Validates JWTs");
        ServiceDependencyEdge downstream = new ServiceDependencyEdge(
                UUID.randomUUID(), "billing-service",
                UUID.randomUUID(), "checkout-service",
                "Reads invoices");

        DatabaseUsage db = new DatabaseUsage(UUID.randomUUID(), "billing-db", "postgres", true, "Primary store");

        ExternalDependencyUsage ext = new ExternalDependencyUsage(
                UUID.randomUUID(), "Stripe", "https://stripe.com", "Payment processor");

        ChangeEntry change = new ChangeEntry(
                OffsetDateTime.parse("2026-04-26T10:15:00Z"),
                "intake-agent", "update", "fields_updated: name, owner_team");

        return new ServicePageContext(
                s,
                List.of(api1Pres),
                List.of(upstream),
                List.of(downstream),
                List.of(db),
                List.of(ext),
                List.of(change));
    }

    private ServicePageContext minimalContext() {
        Service s = new Service();
        s.setName("minimal-service");
        s.setStatus(ServiceStatus.IN_DEV);
        return emptyContextFor(s);
    }

    private ServicePageContext emptyContextFor(Service s) {
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private Service baseService() {
        Service s = new Service();
        s.setName("base");
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }
}
