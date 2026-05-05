package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Markdown counterpart of {@link ArchitectureMapRenderer} (Phase 5.8 M2).
 * Mermaid renders inside Obsidian via the built-in Mermaid plugin (recent
 * versions have it enabled by default); a fenced ```mermaid block is the
 * GFM-standard way to embed it. The plain-text fallback list still appears
 * underneath so the data is readable even where the mermaid plugin is off.
 */
@Component
public class ArchitectureMapMarkdownRenderer {

    private static final String PAGE_TITLE = "Atlas — Architecture Map";

    public String render(List<Service> services,
                         List<ServiceDependencyEdge> edges,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(PAGE_TITLE, "architecture-map", lastSyncAt)));

        sb.append("# ").append(PAGE_TITLE).append("\n\n");
        sb.append("## Service Dependency Map\n\n");
        sb.append("This map is auto-generated from the Atlas database on every sync. Each box is a ");
        sb.append("service; each arrow points from an upstream service to a downstream service that ");
        sb.append("depends on it.\n\n");

        if (lastSyncAt != null) {
            sb.append("**Last refreshed:** ").append(lastSyncAt).append("\n\n");
        }

        if (services == null || services.isEmpty()) {
            sb.append("*No services registered yet. Run intake to add the first one.*\n");
            return sb.toString();
        }

        Map<UUID, String> nodeIds = assignNodeIds(services);

        sb.append(renderMermaidBlock(services, edges, nodeIds));
        sb.append('\n');
        sb.append(renderFallbackList(edges));

        return sb.toString();
    }

    public static String pageTitle() {
        return PAGE_TITLE;
    }

    private Map<UUID, String> assignNodeIds(List<Service> services) {
        Map<UUID, String> ids = new HashMap<>();
        int i = 1;
        for (Service s : services) {
            if (s.getId() != null) {
                ids.put(s.getId(), "n" + i);
            }
            i++;
        }
        return ids;
    }

    private String renderMermaidBlock(List<Service> services,
                                      List<ServiceDependencyEdge> edges,
                                      Map<UUID, String> nodeIds) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("```mermaid\n");
        mermaid.append("flowchart LR\n");
        for (Service s : services) {
            String nodeId = nodeIds.get(s.getId());
            if (nodeId == null) continue;
            mermaid.append("  ").append(nodeId).append("[")
                    .append(sanitizeForMermaidLabel(s.getName())).append("]\n");
        }
        for (ServiceDependencyEdge e : edges) {
            String upstream = nodeIds.get(e.upstreamServiceId());
            String downstream = nodeIds.get(e.downstreamServiceId());
            if (upstream == null || downstream == null) continue;
            mermaid.append("  ").append(upstream).append(" --> ").append(downstream).append('\n');
        }
        mermaid.append("```\n");
        return mermaid.toString();
    }

    private String renderFallbackList(List<ServiceDependencyEdge> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Edges (text fallback)\n\n");
        if (edges == null || edges.isEmpty()) {
            sb.append("*No service-to-service dependencies recorded yet.*\n");
            return sb.toString();
        }
        for (ServiceDependencyEdge e : edges) {
            sb.append("- ").append(e.upstreamServiceName())
                    .append(" → ").append(e.downstreamServiceName());
            if (e.description() != null && !e.description().isBlank()) {
                sb.append(" — ").append(e.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Mermaid node labels in {@code [ ]} can't safely contain raw quotes,
     * brackets, or pipe chars. Replace any with safe substitutes — we don't
     * HTML-escape because the GFM mermaid renderer doesn't decode entities.
     */
    private String sanitizeForMermaidLabel(String s) {
        if (s == null) return "";
        return s.replace("[", "(")
                .replace("]", ")")
                .replace("|", "/")
                .replace("\"", "'");
    }
}
