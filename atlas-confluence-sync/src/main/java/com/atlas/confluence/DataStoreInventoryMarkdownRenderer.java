package com.atlas.confluence;

import com.atlas.services.DataStoreInventoryRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Markdown counterpart of {@link DataStoreInventoryRenderer} (Phase 5.8 M2).
 * Service references render as Obsidian WikiLinks; the {@code servicePageRefs}
 * map carries the WikiLink target per service id.
 */
@Component
public class DataStoreInventoryMarkdownRenderer {

    private static final String PAGE_TITLE = "Inventory: Data Stores";

    public String render(List<DataStoreInventoryRow> rows,
                         Map<UUID, String> servicePageRefs,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(PAGE_TITLE, "inventory", lastSyncAt)));

        sb.append("# ").append(PAGE_TITLE).append("\n\n");

        sb.append("## About this page\n\n");
        sb.append("Every data store registered in the Atlas inventory, with the services that use ");
        sb.append("each one. Owned-by relationships are marked. Auto-generated from the central Atlas ");
        sb.append("database — see the per-service pages for the inverse view.\n\n");
        if (lastSyncAt != null) {
            sb.append("**Last refreshed:** ").append(lastSyncAt).append("\n\n");
        }

        sb.append("## Data stores\n\n");

        Map<UUID, List<DataStoreInventoryRow>> grouped = groupByDataStore(rows);
        if (grouped.isEmpty()) {
            sb.append("*No data stores registered yet.*\n");
            return sb.toString();
        }

        for (Map.Entry<UUID, List<DataStoreInventoryRow>> entry : grouped.entrySet()) {
            List<DataStoreInventoryRow> storeRows = entry.getValue();
            DataStoreInventoryRow first = storeRows.get(0);

            sb.append("### ").append(first.dataStoreName());
            if (first.engine() != null && !first.engine().isBlank()) {
                sb.append(" (").append(first.engine()).append(")");
            }
            sb.append("\n\n");

            List<DataStoreInventoryRow> withService = storeRows.stream()
                    .filter(r -> r.serviceId() != null)
                    .toList();

            if (withService.isEmpty()) {
                sb.append("*No services use this data store yet.*\n\n");
                continue;
            }

            for (DataStoreInventoryRow r : withService) {
                sb.append("- ");
                String ref = servicePageRefs == null ? null : servicePageRefs.get(r.serviceId());
                if (ref != null && !ref.isBlank()) {
                    sb.append(ref.equals(r.serviceName())
                            ? MarkdownRenderingUtil.wikiLink(ref)
                            : MarkdownRenderingUtil.wikiLink(ref, r.serviceName()));
                } else {
                    sb.append(r.serviceName());
                }
                if (Boolean.TRUE.equals(r.isOwner())) {
                    sb.append(" — **owner**");
                }
                if (r.description() != null && !r.description().isBlank()) {
                    sb.append(" — ").append(r.description());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    public static String pageTitle() {
        return PAGE_TITLE;
    }

    private Map<UUID, List<DataStoreInventoryRow>> groupByDataStore(List<DataStoreInventoryRow> rows) {
        Map<UUID, List<DataStoreInventoryRow>> grouped = new LinkedHashMap<>();
        for (DataStoreInventoryRow r : rows) {
            grouped.computeIfAbsent(r.dataStoreId(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }
}
