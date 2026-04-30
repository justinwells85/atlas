package com.atlas.codesync;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link OpenApiParser} backed by the {@code swagger-parser} library. Accepts
 * either JSON or YAML; the parser auto-detects.
 *
 * Endpoint description prefers the operation's {@code summary} (the one-line
 * label), falling back to {@code description}. Auth is summarised as the name
 * of the first {@link SecurityRequirement} declared on the operation, or — if
 * none — the first global security requirement; matched against the spec's
 * {@code components.securitySchemes} to render the scheme type ("bearer",
 * "apiKey", etc) when available.
 */
@Component
public class SwaggerOpenApiParser implements OpenApiParser {

    /**
     * Jackson mapper used to serialize swagger-models POJOs to JSON for the
     * per-endpoint snapshot. Swagger's models carry Jackson annotations, so
     * a stock mapper produces faithful JSON. Sort keys for stable string
     * comparison — the {@link CodeSyncCoordinator}'s change-detection
     * compares snapshot strings to decide whether a new observation is
     * needed.
     */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            // Swagger's Schema POJOs declare ~80 nullable fields each; without
            // null-suppression a single Operation snapshot balloons into many
            // kilobytes of mostly-null noise. Storage cost aside, the renderer
            // just walks fields it cares about — null suppression keeps the
            // persisted snapshot human-readable in psql.
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Override
    public List<EndpointRecord> parse(String specText) {
        // Resolve $ref schemas inline so the snapshot is self-contained:
        // the per-endpoint renderer never needs to look up components/schemas.
        ParseOptions options = new ParseOptions();
        options.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(specText, null, options);
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            throw new IllegalArgumentException(
                    "OpenAPI spec could not be parsed: " + result.getMessages());
        }

        Map<String, SecurityScheme> securitySchemes = api.getComponents() != null
                ? api.getComponents().getSecuritySchemes()
                : null;
        List<SecurityRequirement> globalSecurity = api.getSecurity();

        List<EndpointRecord> out = new ArrayList<>();
        if (api.getPaths() == null) {
            return out;
        }
        for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
            String path = e.getKey();
            PathItem item = e.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                Operation operation = op.getValue();
                String description = pickDescription(operation);
                String authMethod = pickAuthMethod(operation, globalSecurity, securitySchemes);
                String snapshot = buildSnapshot(operation);
                out.add(new EndpointRecord(
                        op.getKey().name(),
                        path,
                        description,
                        authMethod,
                        snapshot));
            }
        }
        return out;
    }

    /**
     * Build a JSON snapshot of the operation's parameters, request body, and
     * responses. Returns {@code null} when the operation has nothing
     * snapshot-worthy — keeps intake-source-equivalent operations from
     * carrying a wasteful empty document.
     */
    private static String buildSnapshot(Operation op) {
        boolean hasParams = op.getParameters() != null && !op.getParameters().isEmpty();
        boolean hasRequestBody = op.getRequestBody() != null;
        boolean hasResponses = op.getResponses() != null && !op.getResponses().isEmpty();
        if (!hasParams && !hasRequestBody && !hasResponses) {
            return null;
        }
        ObjectNode root = SNAPSHOT_MAPPER.createObjectNode();
        if (hasParams) {
            root.set("parameters", SNAPSHOT_MAPPER.valueToTree(op.getParameters()));
        }
        if (hasRequestBody) {
            root.set("requestBody", SNAPSHOT_MAPPER.valueToTree(op.getRequestBody()));
        }
        if (hasResponses) {
            root.set("responses", SNAPSHOT_MAPPER.valueToTree(op.getResponses()));
        }
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException jpe) {
            // Should never happen — we built the tree from in-memory POJOs.
            // If it does, fall back to no snapshot rather than failing the
            // whole parse: the endpoint still gets created with method/path.
            return null;
        }
    }

    private static String pickDescription(Operation op) {
        if (op.getSummary() != null && !op.getSummary().isBlank()) {
            return op.getSummary();
        }
        return op.getDescription();
    }

    private static String pickAuthMethod(Operation op,
                                         List<SecurityRequirement> globalSecurity,
                                         Map<String, SecurityScheme> schemes) {
        List<SecurityRequirement> reqs = op.getSecurity() != null
                ? op.getSecurity()
                : globalSecurity;
        if (reqs == null || reqs.isEmpty()) {
            return null;
        }
        for (SecurityRequirement req : reqs) {
            for (String name : req.keySet()) {
                if (schemes != null && schemes.containsKey(name)) {
                    SecurityScheme scheme = schemes.get(name);
                    return describeScheme(scheme);
                }
                return name;
            }
        }
        return null;
    }

    private static String describeScheme(SecurityScheme scheme) {
        if (scheme == null || scheme.getType() == null) {
            return null;
        }
        return switch (scheme.getType()) {
            case HTTP -> scheme.getScheme() != null ? scheme.getScheme() : "http";
            case APIKEY -> "apiKey";
            case OAUTH2 -> "oauth2";
            case OPENIDCONNECT -> "openIdConnect";
            case MUTUALTLS -> "mutualTLS";
        };
    }
}
