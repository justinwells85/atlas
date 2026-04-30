package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

/**
 * Renders one per-endpoint Confluence page (Phase 5.6 M1 — L3 of the drill-down).
 * Sections: heading (method+path), source-provenance badge, description (or
 * thin "No description documented" note), auth, parameters table, request body
 * schema, responses by status code, examples, spec link (openapi-source only),
 * back-link to the service page.
 *
 * <p>The schema-level sections (parameters, request body, responses, examples)
 * are read from {@link ApiSummary#openapiSnapshot()}, a JSON document captured
 * at code-sync time with {@code $ref} references resolved inline. The renderer
 * is one level deep: for object schemas it lists the top-level fields with
 * type and description, but does not recurse into nested objects — those
 * collapse to their type name and the spec link is the canonical source for
 * the full detail.
 *
 * <p>Pure function: no I/O.
 */
@Component
public class ApiEndpointPageRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String render(ApiEndpointPageContext ctx) {
        Service s = ctx.service();
        ApiSummary api = ctx.api();
        JsonNode snapshot = parseSnapshot(api.openapiSnapshot());

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(api.method())).append(" ").append(escape(api.path())).append("</h2>\n");

        // Source provenance badge — makes the documented-by-whom story visible.
        sb.append("<p><strong>Source:</strong> ").append(escape(api.source())).append("</p>\n");

        // Description
        if (hasText(api.description())) {
            sb.append("<p>").append(escape(api.description())).append("</p>\n");
        } else {
            sb.append("<p><em>No description documented.</em></p>\n");
        }

        // Auth (omitted entirely when null/blank)
        if (hasText(api.authMethod())) {
            sb.append("<p><strong>Auth:</strong> ").append(escape(api.authMethod())).append("</p>\n");
        }

        if (snapshot != null) {
            renderParameters(sb, snapshot.get("parameters"));
            renderRequestBody(sb, snapshot.get("requestBody"));
            renderResponses(sb, snapshot.get("responses"));
        }

        // For openapi-source rows, link to the full spec for schema detail.
        // Intake-source rows aren't tied to the spec; pointing at it would mislead.
        if ("openapi".equals(api.source()) && hasText(s.getOpenapiSpecUrl())) {
            sb.append("<p>For the full canonical spec, see the OpenAPI document: ")
                    .append(renderLink(s.getOpenapiSpecUrl(), s.getOpenapiSpecUrl()))
                    .append("</p>\n");
        }

        // Back-link to the service page, if known.
        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("<p>Back to ")
                    .append(renderLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append("</p>\n");
        }

        return sb.toString();
    }

    // ---- L3 schema sections ---------------------------------------------

    private static JsonNode parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            return MAPPER.readTree(snapshotJson);
        } catch (Exception e) {
            // A bad snapshot shouldn't block the page — fall back to no
            // schema rendering. Code-sync produced this string so we trust
            // it in the happy path; defensive parse here keeps a regression
            // in the producer from breaking the renderer.
            return null;
        }
    }

    private void renderParameters(StringBuilder sb, JsonNode parameters) {
        if (parameters == null || !parameters.isArray() || parameters.isEmpty()) return;
        sb.append("<h3>Parameters</h3>\n");
        sb.append("<table><tbody>\n");
        sb.append("<tr><th>Name</th><th>In</th><th>Type</th><th>Required</th><th>Description</th></tr>\n");
        for (JsonNode p : parameters) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(text(p, "name"))).append("</td>");
            sb.append("<td>").append(escape(text(p, "in"))).append("</td>");
            sb.append("<td>").append(escape(schemaTypeName(p.get("schema")))).append("</td>");
            sb.append("<td>").append(p.path("required").asBoolean(false) ? "yes" : "no").append("</td>");
            sb.append("<td>").append(escape(text(p, "description"))).append("</td>");
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table>\n");
    }

    private void renderRequestBody(StringBuilder sb, JsonNode requestBody) {
        if (requestBody == null || requestBody.isMissingNode()) return;
        JsonNode content = requestBody.get("content");
        if (content == null || !content.isObject() || content.isEmpty()) return;
        sb.append("<h3>Request Body</h3>\n");
        renderContentMap(sb, content);
    }

    private void renderResponses(StringBuilder sb, JsonNode responses) {
        if (responses == null || !responses.isObject() || responses.isEmpty()) return;
        sb.append("<h3>Responses</h3>\n");
        Iterator<Map.Entry<String, JsonNode>> entries = responses.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> e = entries.next();
            String status = e.getKey();
            JsonNode resp = e.getValue();
            sb.append("<h4>").append(escape(status));
            String description = text(resp, "description");
            if (hasText(description)) {
                sb.append(" — ").append(escape(description));
            }
            sb.append("</h4>\n");
            JsonNode content = resp.get("content");
            if (content != null && content.isObject() && !content.isEmpty()) {
                renderContentMap(sb, content);
            }
        }
    }

    /**
     * Render a {@code content: { mediaType: { schema, example } }} map. Used
     * by both Request Body and Responses since they share the same shape in
     * OpenAPI 3.x.
     */
    private void renderContentMap(StringBuilder sb, JsonNode content) {
        Iterator<Map.Entry<String, JsonNode>> mediaTypes = content.fields();
        while (mediaTypes.hasNext()) {
            Map.Entry<String, JsonNode> e = mediaTypes.next();
            sb.append("<p><strong>").append(escape(e.getKey())).append("</strong></p>\n");
            JsonNode media = e.getValue();
            JsonNode schema = media.get("schema");
            if (schema != null) {
                renderSchemaTopLevel(sb, schema);
            }
            JsonNode example = media.get("example");
            if (example != null && !example.isNull()) {
                sb.append("<p><em>Example:</em></p>\n<pre>")
                        .append(escape(prettyJson(example)))
                        .append("</pre>\n");
            }
        }
    }

    /**
     * Top-level schema rendering: one level deep. For object types, list each
     * property with its declared type, required flag, and description. Nested
     * objects collapse to their type name only — the spec link is the
     * canonical detail source.
     */
    private void renderSchemaTopLevel(StringBuilder sb, JsonNode schema) {
        String type = text(schema, "type");
        if ("object".equals(type)) {
            JsonNode props = schema.get("properties");
            if (props == null || !props.isObject() || props.isEmpty()) {
                sb.append("<p><em>object</em> (no properties documented)</p>\n");
                return;
            }
            java.util.Set<String> required = new java.util.HashSet<>();
            JsonNode requiredArr = schema.get("required");
            if (requiredArr != null && requiredArr.isArray()) {
                requiredArr.forEach(n -> required.add(n.asText()));
            }
            sb.append("<table><tbody>\n");
            sb.append("<tr><th>Field</th><th>Type</th><th>Required</th><th>Description</th></tr>\n");
            Iterator<Map.Entry<String, JsonNode>> ps = props.fields();
            while (ps.hasNext()) {
                Map.Entry<String, JsonNode> e = ps.next();
                sb.append("<tr>");
                sb.append("<td>").append(escape(e.getKey())).append("</td>");
                sb.append("<td>").append(escape(schemaTypeName(e.getValue()))).append("</td>");
                sb.append("<td>").append(required.contains(e.getKey()) ? "yes" : "no").append("</td>");
                sb.append("<td>").append(escape(text(e.getValue(), "description"))).append("</td>");
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");
            return;
        }
        if ("array".equals(type)) {
            JsonNode items = schema.get("items");
            sb.append("<p><em>array of </em>")
                    .append(escape(schemaTypeName(items))).append("</p>\n");
            return;
        }
        if (hasText(type)) {
            sb.append("<p><em>").append(escape(type)).append("</em></p>\n");
        }
    }

    /**
     * Pick a short type-name for a schema node — one level only. Inner
     * objects/arrays collapse to "object"/"array of X" rather than recursing.
     */
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

    /**
     * Stable Confluence page title: {@code "{service} — {METHOD} {path}"}. The
     * em-dash matches the convention from other Atlas-managed pages
     * ("Atlas — Service Inventory" etc.) so service-page descendants sort
     * naturally in the sidebar tree.
     */
    public static String pageTitle(Service service, ApiSummary api) {
        return service.getName() + " — " + api.method() + " " + api.path();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String renderLink(String href, String text) {
        return "<a href=\"" + escape(href) + "\">" + escape(text) + "</a>";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
