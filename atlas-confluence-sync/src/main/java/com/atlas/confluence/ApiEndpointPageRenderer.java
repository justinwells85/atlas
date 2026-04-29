package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import org.springframework.stereotype.Component;

/**
 * Renders one per-endpoint Confluence page (M2). Heading shows method+path,
 * body shows description (or a thin "No description documented" note),
 * optional auth, source-provenance badge ({@code openapi} / {@code intake}),
 * a back-link to the parent service page, and — for openapi-source rows —
 * a link to the full OpenAPI spec for schema-level detail.
 *
 * Pure function: no I/O. Schema-level rendering (parameters, request/response
 * shape) is deferred to a future iteration; today the page links out to the
 * spec for that detail.
 */
@Component
public class ApiEndpointPageRenderer {

    public String render(ApiEndpointPageContext ctx) {
        Service s = ctx.service();
        ApiSummary api = ctx.api();

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

        // For openapi-source rows, link to the full spec for schema detail.
        // Intake-source rows aren't tied to the spec; pointing at it would mislead.
        if ("openapi".equals(api.source()) && hasText(s.getOpenapiSpecUrl())) {
            sb.append("<p>For full request/response schemas, see the OpenAPI spec: ")
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

    /**
     * Stable Confluence page title: {@code "{service} — {METHOD} {path}"}. The
     * em-dash matches the convention from other Atlas-managed pages
     * ("Atlas — Service Inventory" etc.) so service-page descendants sort
     * naturally in the sidebar tree.
     */
    public static String pageTitle(Service service, ApiSummary api) {
        return service.getName() + " — " + api.method() + " " + api.path();
    }

    private boolean hasText(String s) {
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
