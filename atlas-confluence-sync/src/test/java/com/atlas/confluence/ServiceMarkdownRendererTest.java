package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ApiSummary;
import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceModule;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceMarkdownRendererTest {

    private final ServiceMarkdownRenderer renderer = new ServiceMarkdownRenderer();

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: Service: billing-service");
        assertThat(rendered).contains("atlas_page_type: service");
        assertThat(rendered).contains("---\n\n# Service: billing-service");
    }

    @Test
    void whenServiceHasFullData_thenAllEightSectionHeadersAreRendered() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("## Overview")
                .contains("## Technical Details")
                .contains("## APIs")
                .contains("## Dependencies")
                .contains("## Data")
                .contains("## Operational")
                .contains("## Change History")
                .contains("## Internals");
    }

    @Test
    void whenServiceHasFullData_thenOverviewIncludesDescriptionOwnerAndStatus() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("Handles billing for active subscriptions.")
                .contains("**Owner:** Platform team")
                .contains("**Status:** active");
    }

    @Test
    void whenServiceHasFullData_thenTechnicalDetailsIncludeLanguageFrameworkRepoLinkAndDeployment() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("**Language:** Java 21")
                .contains("**Framework:** Spring Boot 4")
                .contains("**Deployment:** AWS ECS")
                .contains("**Repository:** [https://github.com/example/billing-service](https://github.com/example/billing-service)");
    }

    @Test
    void whenServiceHasFullData_thenApisSectionListsMethodPathDescriptionAndConsumers() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("- **GET /v1/invoices**")
                .contains("Invoice list endpoint")
                .contains("auth: bearer")
                .contains("Consumers:")
                .contains("checkout-service");
    }

    @Test
    void whenServiceHasFullData_thenDependenciesListUpstreamDownstreamDatabasesAndExternalDeps() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("### Databases")
                .contains("billing-db")
                .contains("(postgres)")
                .contains("### Upstream Services")
                .contains("auth-service")
                .contains("### Downstream Services")
                .contains("checkout-service")
                .contains("### External Dependencies")
                .contains("Stripe");
    }

    @Test
    void whenServiceHasOwnedDb_thenDataSectionListsItAlongsideClassification() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("### Owned Databases")
                .contains("- **billing-db**")
                .contains("### Data Classification")
                .contains("PII");
    }

    @Test
    void whenServiceHasOperationalData_thenSectionShowsContactSlaNotes() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("**Support Contact:** billing-oncall@example.com")
                .contains("**SLA:** 99.9% uptime")
                .contains("**Notes:** Migrating off legacy charge codes Q3.");
    }

    @Test
    void whenServiceHasRecentChanges_thenChangeHistorySectionListsThem() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .contains("### Recent Changes")
                .contains("intake-agent")
                .contains("update")
                .contains("fields_updated: name, owner_team");
    }

    @Test
    void whenMinimalContext_thenAllSectionsHaveThinNotesRatherThanEmpty() {
        String rendered = renderer.render(minimalContext());

        assertThat(rendered)
                .contains("*No technical details documented yet.*")
                .contains("*No APIs documented yet.*")
                .contains("*No databases documented yet.*")
                .contains("*No upstream services documented yet.*")
                .contains("*No downstream services documented yet.*")
                .contains("*No external dependencies documented yet.*")
                .contains("*No owned databases documented yet.*")
                .contains("*No data classification documented yet.*")
                .contains("*No operational details documented yet.*")
                .contains("*No recent changes recorded.*");
    }

    @Test
    void whenServiceConfluencePageUrlMapHasPeer_thenServiceLinkIsWikiLink() {
        Service s = baseService();
        UUID upstreamId = UUID.randomUUID();
        ServiceDependencyEdge edge = new ServiceDependencyEdge(
                upstreamId, "auth-service",
                UUID.randomUUID(), "base", "Validates JWTs");
        ServicePageContext ctx = new ServicePageContext(
                s, List.of(), List.of(edge), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(upstreamId, "auth-service"),
                InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("**[[auth-service]]**");
    }

    @Test
    void whenEndpointHasPageRef_thenApiBulletWrapsTheLabelAsWikiLink() {
        Service s = baseService();
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", null,
                "Health probe", "openapi", "EP_HEALTH");
        ApiPresentation pres = new ApiPresentation(api, List.of(), "base — GET /v1/health");
        ServicePageContext ctx = new ServicePageContext(
                s, List.of(pres), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(),
                InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("[[base — GET /v1/health|GET /v1/health]]");
    }

    @Test
    void whenInternalsHasModulesBeansTestsAndEndpoints_thenAllFourSubBulletsRenderWithLinks() {
        Service s = baseService();
        ServiceModule modA = new ServiceModule(UUID.randomUUID(), s.getId(), "billing-api", "",
                "com.example", "billing-api", "1.0", "jar",
                null, null, null, "[]",
                "pom-xml", null);
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", null,
                "Health probe", "openapi", "EP1");
        ApiPresentation pres = new ApiPresentation(api, List.of(), "base — GET /v1/health");
        Map<String, String> moduleRefs = Map.of("billing-api", "billing-api-module");

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(pres), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(),
                InventoryPageUrls.empty(),
                List.of(modA), moduleRefs, "base — Beans", "base — Tests");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("## Internals")
                .contains("### Modules")
                .contains("[[billing-api-module|billing-api]]")
                .contains("### Code index")
                .contains("[[base — Beans|Beans (Spring stereotype classes)]]")
                .contains("### Tests")
                .contains("[[base — Tests|Test scenarios]]")
                .contains("### Endpoints")
                .contains("[[base — GET /v1/health|GET /v1/health]]");
    }

    @Test
    void whenInternalsLayersAreEmpty_thenEachSubSectionShowsThinNote() {
        ServicePageContext ctx = minimalContext();

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("*No modules documented yet.*")
                .contains("*No code index documented yet.*")
                .contains("*No tests documented yet.*")
                .contains("*No configuration documented yet.*")
                .contains("*No endpoints documented yet.*");
    }

    @Test
    void whenServiceHasConfigurationPage_thenConfigurationSubBulletLinksItBelowTests() {
        // Phase 5.9 M4 — Section 8 gains a Configuration sub-bullet below Tests.
        Service s = baseService();

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), InventoryPageUrls.empty(),
                List.of(), Map.of(), null, null, "base — Configuration");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("### Configuration")
                .contains("[[base — Configuration|Configuration (properties, @Value, @ConfigurationProperties, @Enable*)]]");
        assertThat(rendered.indexOf("### Tests"))
                .isLessThan(rendered.indexOf("### Configuration"));
    }

    @Test
    void whenPomMetadataObservedForLanguage_thenItOverridesEntityColumnAndShowsFromPomSuffix() {
        Service s = baseService();
        s.setLanguage("Python");
        ServiceMetadata pomLanguage = pomMeta(s, "language", "Java 21");

        ServicePageContext ctx = contextWith(s, List.of(pomLanguage));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("**Language:** Java 21 *(from pom.xml)*")
                .doesNotContain("**Language:** Python");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServicePrefix() {
        Service s = baseService();
        s.setName("billing-service");

        assertThat(ServiceMarkdownRenderer.pageTitle(s)).isEqualTo("Service: billing-service");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        String rendered = renderer.render(fullContext());

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<h3>")
                .doesNotContain("<p>")
                .doesNotContain("<ul>")
                .doesNotContain("<a href=")
                .doesNotContain("<strong>");
    }

    // ---- Test fixtures ----------------------------------------------------

    private static ServiceMetadata pomMeta(Service s, String key, String value) {
        return new ServiceMetadata(UUID.randomUUID(), s.getId(), key, value, "pom-xml");
    }

    private ServicePageContext contextWith(Service s, List<ServiceMetadata> metadata) {
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), metadata,
                List.of(), Map.of(), InventoryPageUrls.empty());
    }

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

        ApiSummary api1 = new ApiSummary(UUID.randomUUID(), "/v1/invoices", "GET", "bearer",
                "Invoice list endpoint", "intake", null);
        ApiConsumer consumer = new ApiConsumer(UUID.randomUUID(), "checkout-service", "Reads invoice totals");
        ApiPresentation api1Pres = new ApiPresentation(api1, List.of(consumer), null);

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
                UUID.randomUUID(), "Stripe", "https://stripe.com", "Payment processor", "intake");

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
                List.of(),
                List.of(change),
                Map.of(),
                InventoryPageUrls.empty());
    }

    private ServicePageContext minimalContext() {
        Service s = new Service();
        s.setName("minimal-service");
        s.setStatus(ServiceStatus.IN_DEV);
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(),
                InventoryPageUrls.empty());
    }

    private Service baseService() {
        Service s = new Service();
        s.setName("base");
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }
}
