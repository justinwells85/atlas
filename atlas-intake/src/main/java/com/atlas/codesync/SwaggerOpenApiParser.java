package com.atlas.codesync;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
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

    @Override
    public List<EndpointRecord> parse(String specText) {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(specText);
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
                out.add(new EndpointRecord(
                        op.getKey().name(),
                        path,
                        description,
                        authMethod));
            }
        }
        return out;
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
