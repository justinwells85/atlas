package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-endpoint Confluence page renderer (M2). Each {@link ApiSummary} for a
 * service becomes its own page: heading is {@code METHOD path}, body shows
 * description, auth, source provenance, a back-link to the service page, and —
 * when source=openapi and the service has an {@code openapi_spec_url} — a link
 * to the full spec for schema-level detail (richer schema rendering deferred).
 */
class ApiEndpointPageRendererTest {

    private ApiEndpointPageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ApiEndpointPageRenderer();
    }

    @Test
    void whenEndpointHasDescription_thenPageRendersMethodPathAndDescription() {
        String rendered = renderer.render(ctx(api("POST", "/v1/orders", "Create an order", "bearer", "openapi"),
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/123", "https://intake.example.com/v3/api-docs"));

        assertThat(rendered)
                .contains("POST")
                .contains("/v1/orders")
                .contains("Create an order");
    }

    @Test
    void whenEndpointHasNoDescription_thenPageRendersThinNoteRatherThanEmptySection() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", null, null, "openapi"),
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/123", "https://intake.example.com/v3/api-docs"));

        assertThat(rendered).containsIgnoringCase("no description");
    }

    @Test
    void whenEndpointHasAuthMethod_thenPageShowsAuth() {
        String rendered = renderer.render(ctx(api("POST", "/v1/orders", "Create", "bearer", "openapi"),
                "https://service-page", "https://spec"));

        assertThat(rendered).contains("bearer");
    }

    @Test
    void whenEndpointHasNoAuthMethod_thenPageOmitsAuthLine() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "https://service-page", "https://spec"));

        assertThat(rendered).doesNotContain("auth: ").doesNotContain("Auth:");
    }

    @Test
    void whenEndpointSourceIsOpenapi_thenPageShowsOpenapiBadge() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "https://service-page", "https://spec"));

        assertThat(rendered).containsIgnoringCase("openapi");
    }

    @Test
    void whenEndpointSourceIsIntake_thenPageShowsIntakeBadge() {
        String rendered = renderer.render(ctx(api("POST", "/v1/legacy", "Legacy", "bearer", "intake"),
                "https://service-page", null));

        assertThat(rendered).containsIgnoringCase("intake");
    }

    @Test
    void whenServiceHasConfluencePageUrl_thenBackLinkIsRendered() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "https://example.atlassian.net/wiki/spaces/ATLAS/pages/789",
                "https://spec"));

        assertThat(rendered).contains("https://example.atlassian.net/wiki/spaces/ATLAS/pages/789");
    }

    @Test
    void whenSourceIsOpenapiAndServiceHasSpecUrl_thenLinkToSpecIsRendered() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "https://service-page",
                "https://intake.example.com/v3/api-docs"));

        assertThat(rendered).contains("https://intake.example.com/v3/api-docs");
    }

    @Test
    void whenSourceIsIntake_thenLinkToSpecIsOmittedEvenIfServiceHasSpecUrl() {
        // intake-source rows aren't tied to the OpenAPI spec; pointing at it
        // would imply the description came from there, which is wrong.
        String rendered = renderer.render(ctx(api("POST", "/v1/legacy", "Legacy", null, "intake"),
                "https://service-page",
                "https://intake.example.com/v3/api-docs"));

        assertThat(rendered).doesNotContain("https://intake.example.com/v3/api-docs");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashMethodPath() {
        Service s = service("atlas-intake");
        ApiSummary a = api("POST", "/api/intake/turn", "Multi-turn", null, "openapi");

        String title = ApiEndpointPageRenderer.pageTitle(s, a);

        assertThat(title).isEqualTo("atlas-intake — POST /api/intake/turn");
    }

    // ---- helpers --------------------------------------------------------

    private ApiEndpointPageContext ctx(ApiSummary api, String serviceUrl, String specUrl) {
        Service s = service("atlas-intake");
        s.setOpenapiSpecUrl(specUrl);
        return new ApiEndpointPageContext(s, api, serviceUrl);
    }

    private Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }

    private ApiSummary api(String method, String path, String description,
                           String auth, String source) {
        return new ApiSummary(UUID.randomUUID(), path, method, auth, description, source, null);
    }
}
