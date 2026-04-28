package com.atlas.confluence;

import com.atlas.services.DataStoreInventoryRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the data-store inventory page — a per-data-store view that
 * complements the per-service view of the individual service pages. Answers
 * "who uses checkout-db?" without scanning every service page.
 *
 * Pure function: takes flat join rows from the repository, groups them by
 * data store in render order, links each using service to its Confluence
 * page where available.
 */
@Component
public class DataStoreInventoryRenderer {

    public String render(List<DataStoreInventoryRow> rows,
                         Map<UUID, String> serviceConfluencePageUrls,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>About this page</h2>\n");
        sb.append("<p>Every data store registered in the Atlas inventory, with the services that " +
                "use each one. Owned-by relationships are marked. Auto-generated from the central " +
                "Atlas database — see the per-service pages for the inverse view.</p>\n");
        if (lastSyncAt != null) {
            sb.append("<p><strong>Last refreshed:</strong> ").append(escape(lastSyncAt.toString())).append("</p>\n");
        }

        sb.append("<h2>Data stores</h2>\n");

        Map<UUID, List<DataStoreInventoryRow>> grouped = groupByDataStore(rows);
        if (grouped.isEmpty()) {
            appendThinNote(sb, "No data stores registered yet.");
            return sb.toString();
        }

        for (Map.Entry<UUID, List<DataStoreInventoryRow>> entry : grouped.entrySet()) {
            List<DataStoreInventoryRow> storeRows = entry.getValue();
            DataStoreInventoryRow first = storeRows.get(0);

            sb.append("<h3>").append(escape(first.dataStoreName()));
            if (first.engine() != null && !first.engine().isBlank()) {
                sb.append(" (").append(escape(first.engine())).append(")");
            }
            sb.append("</h3>\n");

            // Filter to rows that actually have a using service (the LEFT JOIN can
            // emit a single row with null service fields when the store has no users).
            List<DataStoreInventoryRow> withService = storeRows.stream()
                    .filter(r -> r.serviceId() != null)
                    .toList();

            if (withService.isEmpty()) {
                appendThinNote(sb, "No services use this data store yet.");
                continue;
            }

            sb.append("<ul>\n");
            for (DataStoreInventoryRow r : withService) {
                sb.append("<li>");
                String url = serviceConfluencePageUrls == null ? null
                        : serviceConfluencePageUrls.get(r.serviceId());
                if (url != null && !url.isBlank()) {
                    sb.append("<a href=\"").append(escape(url)).append("\">")
                            .append(escape(r.serviceName())).append("</a>");
                } else {
                    sb.append(escape(r.serviceName()));
                }
                if (Boolean.TRUE.equals(r.isOwner())) {
                    sb.append(" — <strong>owner</strong>");
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

    private Map<UUID, List<DataStoreInventoryRow>> groupByDataStore(List<DataStoreInventoryRow> rows) {
        Map<UUID, List<DataStoreInventoryRow>> grouped = new LinkedHashMap<>();
        for (DataStoreInventoryRow r : rows) {
            grouped.computeIfAbsent(r.dataStoreId(), k -> new java.util.ArrayList<>()).add(r);
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
