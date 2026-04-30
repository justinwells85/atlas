package com.atlas.codesync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5.6 M1 — verifies the parser extracts the schema-level snapshot
 * (parameters, requestBody, responses) into {@link EndpointRecord#openapiSnapshot()}.
 * The renderer-level shape of that snapshot is the contract the
 * {@code ApiEndpointPageRenderer} consumes; tests here pin the contract.
 */
class SwaggerOpenApiParserTest {

    private final SwaggerOpenApiParser parser = new SwaggerOpenApiParser();

    @Test
    void whenSpecHasOperationWithoutSchemaDetail_thenSnapshotIsNull() {
        String spec = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "T", "version": "1"},
                  "paths": {
                    "/health": {
                      "get": {"summary": "Health"}
                    }
                  }
                }
                """;
        List<EndpointRecord> out = parser.parse(spec);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).openapiSnapshot()).isNull();
    }

    @Test
    void whenSpecHasOperationWithParameters_thenSnapshotCarriesParameters() {
        String spec = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "T", "version": "1"},
                  "paths": {
                    "/users/{id}": {
                      "get": {
                        "summary": "Get user",
                        "parameters": [
                          {"name": "id", "in": "path", "required": true,
                           "schema": {"type": "string"}}
                        ]
                      }
                    }
                  }
                }
                """;
        List<EndpointRecord> out = parser.parse(spec);
        assertThat(out).hasSize(1);
        String snapshot = out.get(0).openapiSnapshot();
        assertThat(snapshot)
                .isNotNull()
                .contains("\"parameters\"")
                .contains("\"id\"")
                .contains("\"path\"");
    }

    @Test
    void whenSpecHasOperationWithRequestBody_thenSnapshotCarriesRequestBody() {
        String spec = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "T", "version": "1"},
                  "paths": {
                    "/users": {
                      "post": {
                        "summary": "Create",
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"type": "object",
                                "properties": {"name": {"type": "string"}}}
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        List<EndpointRecord> out = parser.parse(spec);
        String snapshot = out.get(0).openapiSnapshot();
        assertThat(snapshot)
                .isNotNull()
                .contains("\"requestBody\"")
                .contains("application/json")
                .contains("\"name\"");
    }

    @Test
    void whenSpecHasMultipleResponses_thenSnapshotGroupsByStatusCode() {
        String spec = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "T", "version": "1"},
                  "paths": {
                    "/users/{id}": {
                      "get": {
                        "responses": {
                          "200": {"description": "OK"},
                          "404": {"description": "Not found"}
                        }
                      }
                    }
                  }
                }
                """;
        List<EndpointRecord> out = parser.parse(spec);
        String snapshot = out.get(0).openapiSnapshot();
        assertThat(snapshot)
                .isNotNull()
                .contains("\"responses\"")
                .contains("\"200\"")
                .contains("\"404\"")
                .contains("Not found");
    }

    @Test
    void whenSpecReferencesSchemaViaRef_thenSnapshotInlinesIt() {
        // Resolves $ref so the per-endpoint renderer never has to consult
        // components/schemas. The snapshot is self-contained.
        String spec = """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "T", "version": "1"},
                  "paths": {
                    "/users": {
                      "post": {
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/User"}
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "User": {
                        "type": "object",
                        "properties": {
                          "name": {"type": "string"}
                        }
                      }
                    }
                  }
                }
                """;
        List<EndpointRecord> out = parser.parse(spec);
        String snapshot = out.get(0).openapiSnapshot();
        assertThat(snapshot)
                .isNotNull()
                .contains("\"name\"")
                .contains("\"string\"");
        // The original $ref should not survive resolution.
        assertThat(snapshot).doesNotContain("$ref");
    }
}
