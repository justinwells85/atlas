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
 * Renders the "Atlas — Architecture Map" page: a single Confluence page with
 * a mermaid flowchart of every active service and its service-to-service
 * dependency edges, plus a plain-text fallback list below in case the
 * Confluence instance doesn't render mermaid.
 *
 * Pure function: takes the live service list and the dependency edges
 * (already filtered to non-deleted services by the caller). Mermaid node
 * IDs are derived from a stable per-render counter so service names with
 * spaces, dashes, or punctuation don't break diagram syntax.
 */
@Component
public class ArchitectureMapRenderer {

    public String render(List<Service> services,
                         List<ServiceDependencyEdge> edges,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>Service Dependency Map</h2>\n");
        sb.append("<p>This map is auto-generated from the Atlas database on every sync. " +
                "Each box is a service; each arrow points from an upstream service to a " +
                "downstream service that depends on it.</p>\n");

        if (lastSyncAt != null) {
            sb.append("<p><strong>Last refreshed:</strong> ").append(escape(lastSyncAt.toString())).append("</p>\n");
        }

        if (services == null || services.isEmpty()) {
            sb.append("<p><em>No services registered yet. Run intake to add the first one.</em></p>\n");
            return sb.toString();
        }

        Map<UUID, String> nodeIds = assignNodeIds(services);

        sb.append(renderMermaidBlock(services, edges, nodeIds));
        sb.append(renderFallbackList(edges));

        return sb.toString();
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
        StringBuilder mermaid = new StringBuilder("flowchart LR\n");
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
            mermaid.append("  ").append(upstream).append(" --> ").append(downstream).append("\n");
        }

        // Confluence's `code` macro with language=mermaid renders as a diagram
        // on instances with the macro's mermaid renderer enabled, and as a
        // readable code block everywhere else. Either way the data shows up.
        StringBuilder out = new StringBuilder();
        out.append("<ac:structured-macro ac:name=\"code\" ac:schema-version=\"1\">\n")
                .append("  <ac:parameter ac:name=\"language\">mermaid</ac:parameter>\n")
                .append("  <ac:plain-text-body><![CDATA[")
                .append(mermaid)
                .append("]]></ac:plain-text-body>\n")
                .append("</ac:structured-macro>\n");
        return out.toString();
    }

    private String renderFallbackList(List<ServiceDependencyEdge> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>Edges (text fallback)</h3>\n");
        if (edges == null || edges.isEmpty()) {
            sb.append("<p><em>No service-to-service dependencies recorded yet.</em></p>\n");
            return sb.toString();
        }
        sb.append("<ul>\n");
        for (ServiceDependencyEdge e : edges) {
            sb.append("<li>")
                    .append(escape(e.upstreamServiceName()))
                    .append(" → ")
                    .append(escape(e.downstreamServiceName()));
            if (e.description() != null && !e.description().isBlank()) {
                sb.append(" — ").append(escape(e.description()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
        return sb.toString();
    }

    /**
     * Mermaid node labels in {@code [ ]} can't contain bare brackets or quotes;
     * keep it simple and HTML-escape so any special chars become entities the
     * mermaid parser is fine with (and which still read correctly to a human
     * if the macro renders as a code block instead of a diagram).
     */
    private String sanitizeForMermaidLabel(String s) {
        return escape(s);
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
