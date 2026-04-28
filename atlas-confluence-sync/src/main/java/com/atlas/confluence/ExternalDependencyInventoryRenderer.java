package com.atlas.confluence;

import com.atlas.services.ExternalDependencyInventoryRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the external-dependency inventory page — a per-third-party-tool
 * view of the inventory. Answers "where do we use Stripe?" without
 * scanning every service page.
 */
@Component
public class ExternalDependencyInventoryRenderer {

    public String render(List<ExternalDependencyInventoryRow> rows,
                         Map<UUID, String> serviceConfluencePageUrls,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>About this page</h2>\n");
        sb.append("<p>Every third-party API, SaaS, or tool registered in the Atlas inventory, " +
                "with the services that depend on each one. Auto-generated from the central " +
                "Atlas database — see the per-service pages for the inverse view.</p>\n");
        if (lastSyncAt != null) {
            sb.append("<p><strong>Last refreshed:</strong> ").append(escape(lastSyncAt.toString())).append("</p>\n");
        }

        sb.append("<h2>External dependencies</h2>\n");

        Map<UUID, List<ExternalDependencyInventoryRow>> grouped = groupByDependency(rows);
        if (grouped.isEmpty()) {
            appendThinNote(sb, "No external dependencies registered yet.");
            return sb.toString();
        }

        for (Map.Entry<UUID, List<ExternalDependencyInventoryRow>> entry : grouped.entrySet()) {
            List<ExternalDependencyInventoryRow> depRows = entry.getValue();
            ExternalDependencyInventoryRow first = depRows.get(0);

            sb.append("<h3>");
            if (first.url() != null && !first.url().isBlank()) {
                sb.append("<a href=\"").append(escape(first.url())).append("\">")
                        .append(escape(first.externalDependencyName())).append("</a>");
            } else {
                sb.append(escape(first.externalDependencyName()));
            }
            sb.append("</h3>\n");

            List<ExternalDependencyInventoryRow> withService = depRows.stream()
                    .filter(r -> r.serviceId() != null)
                    .toList();

            if (withService.isEmpty()) {
                appendThinNote(sb, "No services depend on this third-party tool yet.");
                continue;
            }

            sb.append("<ul>\n");
            for (ExternalDependencyInventoryRow r : withService) {
                sb.append("<li>");
                String url = serviceConfluencePageUrls == null ? null
                        : serviceConfluencePageUrls.get(r.serviceId());
                if (url != null && !url.isBlank()) {
                    sb.append("<a href=\"").append(escape(url)).append("\">")
                            .append(escape(r.serviceName())).append("</a>");
                } else {
                    sb.append(escape(r.serviceName()));
                }
                if (r.description() != null && !r.description().isBlank()) {
                    sb.append(" — ").append(escape(r.description()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        return sb.toString();
    }

    private Map<UUID, List<ExternalDependencyInventoryRow>> groupByDependency(
            List<ExternalDependencyInventoryRow> rows) {
        Map<UUID, List<ExternalDependencyInventoryRow>> grouped = new LinkedHashMap<>();
        for (ExternalDependencyInventoryRow r : rows) {
            grouped.computeIfAbsent(r.externalDependencyId(), k -> new java.util.ArrayList<>()).add(r);
        }
        return grouped;
    }

    private void appendThinNote(StringBuilder sb, String message) {
        sb.append("<p><em>").append(escape(message)).append("</em></p>\n");
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
