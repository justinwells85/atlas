package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Markdown counterpart of {@link ApiEndpointPageRenderer} (Phase 5.8 M2).
 * Sections: heading (method+path), source-provenance line, description,
 * auth, parameters table, request body, responses by status, examples
 * (fenced JSON), spec link (openapi only), back-link.
 *
 * <p>Schema rendering is one level deep — same constraint as the Confluence
 * sibling: nested objects collapse to type names. The OpenAPI spec link
 * remains the canonical full-detail source.
 */
@Component
public class ApiEndpointMarkdownRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String render(ApiEndpointPageContext ctx) {
        Service s = ctx.service();
        ApiSummary api = ctx.api();
        JsonNode snapshot = parseSnapshot(api.openapiSnapshot());
        String pageTitle = pageTitle(s, api);

        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(pageTitle, "endpoint", null)));

        sb.append("# ").append(api.method()).append(" ").append(api.path()).append("\n\n");

        sb.append("**Source:** ").append(api.source()).append("\n\n");

        if (hasText(api.description())) {
            sb.append(api.description()).append("\n\n");
        } else {
            sb.append("*No description documented.*\n\n");
        }

        if (hasText(api.authMethod())) {
            sb.append("**Auth:** ").append(api.authMethod()).append("\n\n");
        }

        if (snapshot != null) {
            renderParameters(sb, snapshot.get("parameters"));
            renderRequestBody(sb, snapshot.get("requestBody"));
            renderResponses(sb, snapshot.get("responses"));
        }

        if ("openapi".equals(api.source()) && hasText(s.getOpenapiSpecUrl())) {
            sb.append("For the full canonical spec, see the OpenAPI document: [")
                    .append(s.getOpenapiSpecUrl()).append("](")
                    .append(s.getOpenapiSpecUrl()).append(")\n\n");
        }

        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("Back to ")
                    .append(MarkdownRenderingUtil.wikiLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append('\n');
        }

        return sb.toString();
    }

    public static String pageTitle(Service service, ApiSummary api) {
        return service.getName() + " — " + api.method() + " " + api.path();
    }

    // ---- L3 schema sections ---------------------------------------------

    private static JsonNode parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            return MAPPER.readTree(snapshotJson);
        } catch (Exception e) {
            return null;
        }
    }

    private void renderParameters(StringBuilder sb, JsonNode parameters) {
        if (parameters == null || !parameters.isArray() || parameters.isEmpty()) return;
        sb.append("## Parameters\n\n");
        sb.append("| Name | In | Type | Required | Description |\n");
        sb.append("|---|---|---|---|---|\n");
        for (JsonNode p : parameters) {
            sb.append("| ").append(escapeCell(text(p, "name")));
            sb.append(" | ").append(escapeCell(text(p, "in")));
            sb.append(" | ").append(escapeCell(schemaTypeName(p.get("schema"))));
            sb.append(" | ").append(p.path("required").asBoolean(false) ? "yes" : "no");
            sb.append(" | ").append(escapeCell(text(p, "description")));
            sb.append(" |\n");
        }
        sb.append('\n');
    }

    private void renderRequestBody(StringBuilder sb, JsonNode requestBody) {
        if (requestBody == null || requestBody.isMissingNode()) return;
        JsonNode content = requestBody.get("content");
        if (content == null || !content.isObject() || content.isEmpty()) return;
        sb.append("## Request Body\n\n");
        renderContentMap(sb, content);
    }

    private void renderResponses(StringBuilder sb, JsonNode responses) {
        if (responses == null || !responses.isObject() || responses.isEmpty()) return;
        sb.append("## Responses\n\n");
        Iterator<Map.Entry<String, JsonNode>> entries = responses.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> e = entries.next();
            String status = e.getKey();
            JsonNode resp = e.getValue();
            sb.append("### ").append(status);
            String description = text(resp, "description");
            if (hasText(description)) {
                sb.append(" — ").append(description);
            }
            sb.append("\n\n");
            JsonNode content = resp.get("content");
            if (content != null && content.isObject() && !content.isEmpty()) {
                renderContentMap(sb, content);
            }
        }
    }

    private void renderContentMap(StringBuilder sb, JsonNode content) {
        Iterator<Map.Entry<String, JsonNode>> mediaTypes = content.fields();
        while (mediaTypes.hasNext()) {
            Map.Entry<String, JsonNode> e = mediaTypes.next();
            sb.append("**").append(e.getKey()).append("**\n\n");
            JsonNode media = e.getValue();
            JsonNode schema = media.get("schema");
            if (schema != null) {
                renderSchemaTopLevel(sb, schema);
            }
            JsonNode example = media.get("example");
            if (example != null && !example.isNull()) {
                sb.append("*Example:*\n\n```json\n")
                        .append(prettyJson(example))
                        .append("\n```\n\n");
            }
        }
    }

    private void renderSchemaTopLevel(StringBuilder sb, JsonNode schema) {
        String type = text(schema, "type");
        if ("object".equals(type)) {
            JsonNode props = schema.get("properties");
            if (props == null || !props.isObject() || props.isEmpty()) {
                sb.append("*object* (no properties documented)\n\n");
                return;
            }
            Set<String> required = new HashSet<>();
            JsonNode requiredArr = schema.get("required");
            if (requiredArr != null && requiredArr.isArray()) {
                requiredArr.forEach(n -> required.add(n.asText()));
            }
            sb.append("| Field | Type | Required | Description |\n");
            sb.append("|---|---|---|---|\n");
            Iterator<Map.Entry<String, JsonNode>> ps = props.fields();
            while (ps.hasNext()) {
                Map.Entry<String, JsonNode> e = ps.next();
                sb.append("| ").append(escapeCell(e.getKey()));
                sb.append(" | ").append(escapeCell(schemaTypeName(e.getValue())));
                sb.append(" | ").append(required.contains(e.getKey()) ? "yes" : "no");
                sb.append(" | ").append(escapeCell(text(e.getValue(), "description")));
                sb.append(" |\n");
            }
            sb.append('\n');
            return;
        }
        if ("array".equals(type)) {
            JsonNode items = schema.get("items");
            sb.append("*array of ").append(schemaTypeName(items)).append("*\n\n");
            return;
        }
        if (hasText(type)) {
            sb.append('*').append(type).append("*\n\n");
        }
    }

    private static String schemaTypeName(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return "";
        String type = text(schema, "type");
        if (!hasText(type)) return "";
        if ("array".equals(type)) {
            JsonNode items = schema.get("items");
            String inner = items == null ? "" : text(items, "type");
            return hasText(inner) ? "array of " + inner : "array";
        }
        return type;
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null) return "";
        JsonNode v = parent.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static String prettyJson(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Escape characters that break GFM pipe tables: literal {@code |} must be
     * backslash-escaped; newlines collapse to spaces so a multi-line
     * description doesn't break the row.
     */
    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
