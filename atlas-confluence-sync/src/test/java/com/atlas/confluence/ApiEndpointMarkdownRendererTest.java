package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiEndpointMarkdownRendererTest {

    private ApiEndpointMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ApiEndpointMarkdownRenderer();
    }

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(ctx(api("POST", "/v1/orders", "desc", null, "openapi"),
                null, null));

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: atlas-intake — POST /v1/orders");
        assertThat(rendered).contains("atlas_page_type: endpoint");
        assertThat(rendered).contains("---\n\n# POST /v1/orders");
    }

    @Test
    void whenEndpointHasDescription_thenPageRendersMethodPathAndDescription() {
        String rendered = renderer.render(ctx(api("POST", "/v1/orders", "Create an order", "bearer", "openapi"),
                "atlas-intake", "https://intake.example.com/v3/api-docs"));

        assertThat(rendered)
                .contains("# POST /v1/orders")
                .contains("Create an order");
    }

    @Test
    void whenEndpointHasNoDescription_thenPageRendersThinNote() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", null, null, "openapi"),
                "atlas-intake", "https://spec"));

        assertThat(rendered).contains("*No description documented.*");
    }

    @Test
    void whenEndpointHasAuthMethod_thenPageShowsAuth() {
        String rendered = renderer.render(ctx(api("POST", "/v1/orders", "Create", "bearer", "openapi"),
                "atlas-intake", "https://spec"));

        assertThat(rendered).contains("**Auth:** bearer");
    }

    @Test
    void whenEndpointHasNoAuthMethod_thenPageOmitsAuthLine() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "atlas-intake", "https://spec"));

        assertThat(rendered).doesNotContain("**Auth:**");
    }

    @Test
    void whenEndpointSourceIsOpenapi_thenSourceLineShowsOpenapi() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "atlas-intake", "https://spec"));

        assertThat(rendered).contains("**Source:** openapi");
    }

    @Test
    void whenEndpointSourceIsIntake_thenSourceLineShowsIntake() {
        String rendered = renderer.render(ctx(api("POST", "/v1/legacy", "Legacy", "bearer", "intake"),
                "atlas-intake", null));

        assertThat(rendered).contains("**Source:** intake");
    }

    @Test
    void whenServicePageRefIsProvided_thenWikiLinkBackLinkRendered() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "atlas-intake", "https://spec"));

        assertThat(rendered).contains("Back to [[atlas-intake|atlas-intake]]");
    }

    @Test
    void whenSourceIsOpenapiAndServiceHasSpecUrl_thenLinkToSpecRendered() {
        String rendered = renderer.render(ctx(api("GET", "/v1/health", "Health", null, "openapi"),
                "atlas-intake",
                "https://intake.example.com/v3/api-docs"));

        assertThat(rendered).contains("[https://intake.example.com/v3/api-docs](https://intake.example.com/v3/api-docs)");
    }

    @Test
    void whenSourceIsIntake_thenLinkToSpecOmittedEvenIfServiceHasSpecUrl() {
        String rendered = renderer.render(ctx(api("POST", "/v1/legacy", "Legacy", null, "intake"),
                "atlas-intake",
                "https://intake.example.com/v3/api-docs"));

        assertThat(rendered).doesNotContain("https://intake.example.com/v3/api-docs");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashMethodPath() {
        Service s = service("atlas-intake");
        ApiSummary a = api("POST", "/api/intake/turn", "Multi-turn", null, "openapi");

        assertThat(ApiEndpointMarkdownRenderer.pageTitle(s, a))
                .isEqualTo("atlas-intake — POST /api/intake/turn");
    }

    @Test
    void whenSnapshotDeclaresParameters_thenParametersTableIsRendered() {
        String snapshot = """
                {
                  "parameters": [
                    {"name": "userId", "in": "path", "required": true,
                     "description": "User identifier",
                     "schema": {"type": "string"}}
                  ]
                }
                """;
        ApiSummary a = apiWithSnapshot("GET", "/users/{userId}", "Fetch user", null,
                "openapi", snapshot);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .contains("## Parameters")
                .contains("| Name | In | Type | Required | Description |")
                .contains("| userId | path | string | yes | User identifier |");
    }

    @Test
    void whenSnapshotHasNoParameters_thenParametersSectionIsOmitted() {
        ApiSummary a = apiWithSnapshot("GET", "/health", "Health", null, "openapi", "{}");
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered).doesNotContain("## Parameters");
    }

    @Test
    void whenSnapshotDeclaresRequestBodyWithObjectSchema_thenRequestBodyRendered() {
        String snapshot = """
                {
                  "requestBody": {
                    "content": {
                      "application/json": {
                        "schema": {
                          "type": "object",
                          "required": ["name"],
                          "properties": {
                            "name": {"type": "string", "description": "Display name"},
                            "age": {"type": "integer"}
                          }
                        }
                      }
                    }
                  }
                }
                """;
        ApiSummary a = apiWithSnapshot("POST", "/users", "Create user", null,
                "openapi", snapshot);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .contains("## Request Body")
                .contains("**application/json**")
                .contains("| Field | Type | Required | Description |")
                .contains("| name | string | yes | Display name |")
                .contains("| age | integer | no |");
    }

    @Test
    void whenSnapshotDeclaresMultipleResponses_thenResponsesGroupedByStatusCode() {
        String snapshot = """
                {
                  "responses": {
                    "200": {"description": "OK",
                            "content": {"application/json": {"schema": {"type": "object",
                                "properties": {"id": {"type": "string"}}}}}},
                    "404": {"description": "Not found"}
                  }
                }
                """;
        ApiSummary a = apiWithSnapshot("GET", "/users/{id}", "Get user", null,
                "openapi", snapshot);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .contains("## Responses")
                .contains("### 200 — OK")
                .contains("### 404 — Not found");
    }

    @Test
    void whenSnapshotHasInlineExample_thenExampleRenderedAsFencedJson() {
        String snapshot = """
                {
                  "requestBody": {
                    "content": {
                      "application/json": {
                        "schema": {"type": "object"},
                        "example": {"name": "Alice", "age": 30}
                      }
                    }
                  }
                }
                """;
        ApiSummary a = apiWithSnapshot("POST", "/users", "Create user", null,
                "openapi", snapshot);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .contains("*Example:*")
                .contains("```json")
                .contains("Alice")
                .contains("30")
                .contains("```");
    }

    @Test
    void whenSnapshotIsNull_thenNoSchemaSectionsRendered() {
        ApiSummary a = apiWithSnapshot("GET", "/health", "Health", null, "openapi", null);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .doesNotContain("## Parameters")
                .doesNotContain("## Request Body")
                .doesNotContain("## Responses");
    }

    @Test
    void whenSchemaIsDeeplyNested_thenInnerLevelCollapsesToTypeName() {
        String snapshot = """
                {
                  "requestBody": {
                    "content": {
                      "application/json": {
                        "schema": {
                          "type": "object",
                          "properties": {
                            "address": {
                              "type": "object",
                              "properties": {
                                "street": {"type": "string"},
                                "city": {"type": "string"}
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        ApiSummary a = apiWithSnapshot("POST", "/users", "Create", null, "openapi", snapshot);
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .contains("address")
                .doesNotContain("street")
                .doesNotContain("city");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        ApiSummary a = api("POST", "/v1/orders", "Create", "bearer", "openapi");
        String rendered = renderer.render(ctx(a, "atlas-intake", "https://spec"));

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<table>")
                .doesNotContain("<a href=")
                .doesNotContain("<strong>");
    }

    private ApiEndpointPageContext ctx(ApiSummary api, String serviceRef, String specUrl) {
        Service s = service("atlas-intake");
        s.setOpenapiSpecUrl(specUrl);
        return new ApiEndpointPageContext(s, api, serviceRef);
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

    private ApiSummary apiWithSnapshot(String method, String path, String description,
                                       String auth, String source, String snapshot) {
        return new ApiSummary(UUID.randomUUID(), path, method, auth, description, source,
                null, snapshot);
    }
}
