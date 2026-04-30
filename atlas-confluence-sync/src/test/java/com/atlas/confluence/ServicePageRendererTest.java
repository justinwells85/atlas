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
                .contains("No external dependencies documented")
                .contains("No technical details documented")
                .contains("No operational details documented");
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
    void whenServicePageUrlsMapHasUpstreamPeer_thenUpstreamRendersAsHyperlink() {
        Service s = baseService();
        UUID upstreamId = UUID.randomUUID();
        ServiceDependencyEdge edge = new ServiceDependencyEdge(
                upstreamId, "auth-service",
                UUID.randomUUID(), "base",
                "JWT validation");

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(),
                List.of(edge),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(upstreamId, "https://example.atlassian.net/wiki/spaces/ATLAS/pages/4242"),
                InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("<a href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/4242\">auth-service</a>")
                .contains("JWT validation");
    }

    @Test
    void whenServicePageUrlsMapMissingPeer_thenUpstreamRendersAsPlainText() {
        Service s = baseService();
        UUID upstreamId = UUID.randomUUID();
        ServiceDependencyEdge edge = new ServiceDependencyEdge(
                upstreamId, "auth-service",
                UUID.randomUUID(), "base",
                "JWT validation");

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(),
                List.of(edge),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), // empty — peer page not yet synced
                InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("auth-service")
                .doesNotContain("<a href=");
    }

    @Test
    void whenServiceHasNoApiConsumers_thenApiRendersWithoutConsumerLine() {
        Service s = baseService();
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", "none", "Health check", "intake", null);
        ServicePageContext ctx = new ServicePageContext(
                s,
                List.of(new ApiPresentation(api, List.of(), null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("/v1/health")
                .contains("Health check")
                .doesNotContain("Consumers:");
    }

    @Test
    void whenApiHasEndpointPageUrl_thenItIsRenderedAsHyperlinkInTheApisSection() {
        Service s = baseService();
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", null,
                "Health probe", "openapi", "EP_HEALTH");
        ServicePageContext ctx = new ServicePageContext(
                s,
                List.of(new ApiPresentation(api, List.of(),
                        "https://atlas.atlassian.net/wiki/spaces/ATLAS/pages/EP_HEALTH")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("href=\"https://atlas.atlassian.net/wiki/spaces/ATLAS/pages/EP_HEALTH\"");
    }

    @Test
    void whenApiHasNoEndpointPageUrl_thenMethodPathRendersAsPlainTextNotALink() {
        Service s = baseService();
        ApiSummary api = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET", null,
                "Health probe", "intake", null);
        ServicePageContext ctx = new ServicePageContext(
                s,
                List.of(new ApiPresentation(api, List.of(), null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), InventoryPageUrls.empty());

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("GET /v1/health");
        // The strong-tag wrapping should not become an anchor when no URL is supplied.
        assertThat(rendered).doesNotContain("href=\"\"");
    }

    // ------------------------------------------------------------------
    // M4.5: technical details from service_metadata + fallback
    // ------------------------------------------------------------------

    @Test
    void whenServiceMetadataHasPomLanguage_thenTechnicalDetailsRenderItWithSuffix() {
        Service s = baseService();
        ServiceMetadata languageObs = pomMeta(s, "language", "Java");
        ServiceMetadata frameworkObs = pomMeta(s, "framework", "Spring Boot");

        ServicePageContext ctx = contextWith(s, List.of(languageObs, frameworkObs));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Java")
                .contains("Spring Boot")
                .contains("(from pom.xml)");
    }

    @Test
    void whenServiceMetadataAbsent_thenTechnicalDetailsFallsBackToEntityColumns() {
        Service s = baseService();
        s.setLanguage("Kotlin (intake)");
        s.setFramework("Ktor (intake)");

        ServicePageContext ctx = contextWith(s, List.of());

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Kotlin (intake)")
                .contains("Ktor (intake)")
                .doesNotContain("(from pom.xml)");
    }

    @Test
    void whenBothServiceMetadataAndEntityColumnPresent_thenServiceMetadataWinsForLanguage() {
        Service s = baseService();
        s.setLanguage("Kotlin (intake)");
        ServiceMetadata pomLang = pomMeta(s, "language", "Java");

        ServicePageContext ctx = contextWith(s, List.of(pomLang));

        String rendered = renderer.render(ctx);

        // pom-source wins
        assertThat(rendered)
                .contains("Java")
                .contains("(from pom.xml)")
                .doesNotContain("Kotlin (intake)");
    }

    @Test
    void whenServiceMetadataHasBuildToolAndVersion_thenTheyAreRendered() {
        Service s = baseService();
        ServiceMetadata buildTool = pomMeta(s, "build_tool", "Maven");
        ServiceMetadata langVersion = pomMeta(s, "language_version", "21");

        ServicePageContext ctx = contextWith(s, List.of(buildTool, langVersion));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Maven")
                .contains("21");
    }

    // ------------------------------------------------------------------
    // M4.5: external-dependencies composition (intake + pom)
    // ------------------------------------------------------------------

    @Test
    void whenExternalDepHasOnlyIntakeSource_thenRendersNameAndDescription() {
        Service s = baseService();
        ExternalDependencyUsage intake = new ExternalDependencyUsage(
                UUID.randomUUID(), "Stripe", "https://stripe.com", "Payment processor", "intake");
        ServicePageContext ctx = contextWithDeps(s, List.of(intake));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Stripe")
                .contains("Payment processor")
                .doesNotContain("(from pom.xml)");
    }

    @Test
    void whenExternalDepHasOnlyPomSource_thenRendersCoordinatesWithSuffix() {
        Service s = baseService();
        ExternalDependencyUsage pom = new ExternalDependencyUsage(
                UUID.randomUUID(), "com.fasterxml.jackson.core:jackson-databind", null, null, "pom-xml");
        ServicePageContext ctx = contextWithDeps(s, List.of(pom));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("com.fasterxml.jackson.core:jackson-databind")
                .contains("(from pom.xml)");
    }

    @Test
    void whenIntakeAndPomDepsOverlapByArtifactId_thenIntakeRowIsAnnotatedAndPomRowDropped() {
        // Intake-recorded "Anthropic API" should match pom's "com.anthropic:anthropic-java"
        // because the artifactId "anthropic-java" contains "anthropic" — case-insensitive
        // substring match against the intake name.
        Service s = baseService();
        ExternalDependencyUsage intake = new ExternalDependencyUsage(
                UUID.randomUUID(), "Anthropic API", "https://anthropic.com",
                "LLM provider", "intake");
        ExternalDependencyUsage pom = new ExternalDependencyUsage(
                UUID.randomUUID(), "com.anthropic:anthropic-java", null, null, "pom-xml");
        ServicePageContext ctx = contextWithDeps(s, List.of(intake, pom));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Anthropic API")
                .contains("LLM provider")
                .contains("matches pom: com.anthropic:anthropic-java");
        // The pom row collapsed into the intake row's annotation; no standalone duplicate.
        int firstOccurrence = rendered.indexOf("anthropic-java");
        int lastOccurrence = rendered.lastIndexOf("anthropic-java");
        assertThat(firstOccurrence).isEqualTo(lastOccurrence);
    }

    @Test
    void whenIntakeAndPomDepsDoNotOverlap_thenBothRenderAsSeparateRows() {
        Service s = baseService();
        ExternalDependencyUsage intake = new ExternalDependencyUsage(
                UUID.randomUUID(), "Stripe", "https://stripe.com", "Payment processor", "intake");
        ExternalDependencyUsage pom = new ExternalDependencyUsage(
                UUID.randomUUID(), "com.fasterxml.jackson.core:jackson-databind", null, null, "pom-xml");
        ServicePageContext ctx = contextWithDeps(s, List.of(intake, pom));

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Stripe")
                .contains("com.fasterxml.jackson.core:jackson-databind");
    }

    // ------------------------------------------------------------------
    // Phase 5.6 M4: Section 8 Internals (drill-down cross-reference)
    // ------------------------------------------------------------------

    @Test
    void whenServiceHasModulesEndpointsBeansAndTests_thenInternalsSectionLinksAllFour() {
        Service s = baseService();

        ServiceModule modA = new ServiceModule(UUID.randomUUID(), s.getId(), "billing-api", "",
                "com.example", "billing-api", "1.0", "jar",
                null, null, null, null, "pom-xml", "MOD_A");
        ServiceModule modB = new ServiceModule(UUID.randomUUID(), s.getId(), "billing-core", "",
                "com.example", "billing-core", "1.0", "jar",
                null, null, null, null, "pom-xml", "MOD_B");

        ApiSummary endpoint = new ApiSummary(UUID.randomUUID(), "/v1/health", "GET",
                null, "Health probe", "openapi", "EP1");
        String endpointUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/EP1";
        ApiPresentation endpointPres = new ApiPresentation(endpoint, List.of(), endpointUrl);

        Map<String, String> moduleUrls = Map.of(
                "billing-api", "https://example.atlassian.net/wiki/spaces/ATLAS/pages/MOD_A",
                "billing-core", "https://example.atlassian.net/wiki/spaces/ATLAS/pages/MOD_B");
        String beansUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/BEANS";
        String testsUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/TESTS";

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(endpointPres), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), InventoryPageUrls.empty(),
                List.of(modA, modB), moduleUrls, beansUrl, testsUrl);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("<h2>Internals</h2>")
                .contains("billing-api")
                .contains("billing-core")
                .contains("href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/MOD_A\"")
                .contains("href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/MOD_B\"")
                .contains("href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/BEANS\"")
                .contains("href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/TESTS\"")
                .contains("href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/EP1\"");
    }

    @Test
    void whenServiceHasNoModules_thenModulesSubBulletShowsThinNote() {
        Service s = baseService();
        String beansUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/BEANS";
        String testsUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/TESTS";

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), InventoryPageUrls.empty(),
                List.of(), Map.of(), beansUrl, testsUrl);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("<h2>Internals</h2>")
                .contains("No modules documented yet.");
    }

    @Test
    void whenServiceHasNoBeansPage_thenBeansSubBulletShowsThinNote() {
        Service s = baseService();
        String testsUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/TESTS";

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), InventoryPageUrls.empty(),
                List.of(), Map.of(), null, testsUrl);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("<h2>Internals</h2>")
                .contains("No code index documented yet.");
    }

    @Test
    void whenInternalsSectionRenders_thenLinksUseConfluencePageUrls_notSpecLinks() {
        Service s = baseService();
        ApiSummary endpoint = new ApiSummary(UUID.randomUUID(), "/v1/orders", "GET",
                null, "Orders", "openapi", "EP_ORDERS");
        String endpointUrl = "https://example.atlassian.net/wiki/spaces/ATLAS/pages/EP_ORDERS";
        ApiPresentation pres = new ApiPresentation(endpoint, List.of(), endpointUrl);

        ServiceModule m = new ServiceModule(UUID.randomUUID(), s.getId(), "core", "",
                "com.example", "core", "1.0", "jar",
                null, null, null, null, "pom-xml", "M1");
        Map<String, String> moduleUrls = Map.of("core",
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/M1");

        ServicePageContext ctx = new ServicePageContext(
                s, List.of(pres), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), InventoryPageUrls.empty(),
                List.of(m), moduleUrls,
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/BEANS",
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/TESTS");

        String rendered = renderer.render(ctx);

        int internalsIdx = rendered.indexOf("<h2>Internals</h2>");
        assertThat(internalsIdx).isPositive();
        String internals = rendered.substring(internalsIdx);

        assertThat(internals)
                .contains("/wiki/spaces/ATLAS/pages/")
                .doesNotContain("github.com")
                .doesNotContain("openapi.yaml")
                .doesNotContain("openapi.json")
                .doesNotContain("swagger");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ServiceMetadata pomMeta(Service s, String key, String value) {
        return new ServiceMetadata(UUID.randomUUID(), s.getId(), key, value, "pom-xml");
    }

    private ServicePageContext contextWith(Service s, List<ServiceMetadata> metadata) {
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), metadata,
                List.of(), Map.of(), InventoryPageUrls.empty());
    }

    private ServicePageContext contextWithDeps(Service s, List<ExternalDependencyUsage> deps) {
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), deps, List.of(),
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

        ApiSummary api1 = new ApiSummary(UUID.randomUUID(), "/v1/invoices", "GET", "bearer", "Invoice list endpoint", "intake", null);
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
        return emptyContextFor(s);
    }

    private ServicePageContext emptyContextFor(Service s) {
        return new ServicePageContext(
                s, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                InventoryPageUrls.empty());
    }

    private Service baseService() {
        Service s = new Service();
        s.setName("base");
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }
}
