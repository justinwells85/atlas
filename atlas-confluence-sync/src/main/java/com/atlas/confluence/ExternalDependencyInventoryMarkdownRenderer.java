package com.atlas.confluence;

import com.atlas.services.ExternalDependencyInventoryRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Markdown counterpart of {@link ExternalDependencyInventoryRenderer}
 * (Phase 5.8 M2). Service references render as Obsidian WikiLinks; the
 * external-dependency name links to its homepage URL via standard
 * Markdown link (external URL, not a vault page).
 */
@Component
public class ExternalDependencyInventoryMarkdownRenderer {

    private static final String PAGE_TITLE = "Inventory: External Dependencies";

    public String render(List<ExternalDependencyInventoryRow> rows,
                         Map<UUID, String> servicePageRefs,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(PAGE_TITLE, "inventory", lastSyncAt)));

        sb.append("# ").append(PAGE_TITLE).append("\n\n");

        sb.append("## About this page\n\n");
        sb.append("Every third-party API, SaaS, or tool registered in the Atlas inventory, with the ");
        sb.append("services that depend on each one. Auto-generated from the central Atlas database — ");
        sb.append("see the per-service pages for the inverse view.\n\n");
        if (lastSyncAt != null) {
            sb.append("**Last refreshed:** ").append(lastSyncAt).append("\n\n");
        }

        sb.append("## External dependencies\n\n");

        Map<UUID, List<ExternalDependencyInventoryRow>> grouped = groupByDependency(rows);
        if (grouped.isEmpty()) {
            sb.append("*No external dependencies registered yet.*\n");
            return sb.toString();
        }

        for (Map.Entry<UUID, List<ExternalDependencyInventoryRow>> entry : grouped.entrySet()) {
            List<ExternalDependencyInventoryRow> depRows = entry.getValue();
            ExternalDependencyInventoryRow first = depRows.get(0);

            sb.append("### ");
            if (first.url() != null && !first.url().isBlank()) {
                sb.append('[').append(first.externalDependencyName()).append("](")
                        .append(first.url()).append(')');
            } else {
                sb.append(first.externalDependencyName());
            }
            sb.append("\n\n");

            List<ExternalDependencyInventoryRow> withService = depRows.stream()
                    .filter(r -> r.serviceId() != null)
                    .toList();

            if (withService.isEmpty()) {
                sb.append("*No services depend on this third-party tool yet.*\n\n");
                continue;
            }

            for (ExternalDependencyInventoryRow r : withService) {
                sb.append("- ");
                String ref = servicePageRefs == null ? null : servicePageRefs.get(r.serviceId());
                if (ref != null && !ref.isBlank()) {
                    sb.append(ref.equals(r.serviceName())
                            ? MarkdownRenderingUtil.wikiLink(ref)
                            : MarkdownRenderingUtil.wikiLink(ref, r.serviceName()));
                } else {
                    sb.append(r.serviceName());
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

    private Map<UUID, List<ExternalDependencyInventoryRow>> groupByDependency(
            List<ExternalDependencyInventoryRow> rows) {
        Map<UUID, List<ExternalDependencyInventoryRow>> grouped = new LinkedHashMap<>();
        for (ExternalDependencyInventoryRow r : rows) {
            grouped.computeIfAbsent(r.externalDependencyId(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }
}
