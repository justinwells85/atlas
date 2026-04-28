package com.atlas.confluence;

import com.atlas.services.Service;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the landing page that parents every service sub-page in the Atlas
 * Confluence space — see {@code docs/confluence-layout.md}.
 *
 * The landing page has three responsibilities:
 *  - explain the space (about + source-of-truth statement),
 *  - give readers an at-a-glance index of every documented service,
 *  - timestamp the last sync so staleness is visible.
 *
 * Pure function: takes the list of services + a service-id → page-URL map
 * (built by the coordinator). Re-rendered every sync; Confluence's PUT
 * picks up the new version.
 */
@Component
public class LandingPageRenderer {

    public String render(List<Service> services, Map<UUID, String> serviceConfluencePageUrls,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>About this space</h2>\n");
        sb.append("<p>This is the Atlas service inventory. Every page below is auto-generated " +
                "from the central Atlas database via the intake → DB → Confluence pipeline. ")
                .append("<strong>The database is the source of truth — direct edits to any page in this " +
                        "space will be overwritten on the next sync.</strong></p>\n");
        sb.append("<p><strong>To add your service:</strong> run the intake interview and the page will " +
                "appear here on the next sync (default cadence: every 15 minutes).</p>\n");

        if (lastSyncAt != null) {
            sb.append("<p><strong>Last refreshed:</strong> ").append(escape(lastSyncAt.toString())).append("</p>\n");
        }

        sb.append("<h2>Service index</h2>\n");
        if (services == null || services.isEmpty()) {
            appendThinNote(sb, "No services registered yet. Run intake to add the first one.");
        } else {
            sb.append("<table>\n<tbody>\n");
            sb.append("<tr><th>Service</th><th>Owner</th><th>Status</th><th>Description</th></tr>\n");
            services.stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .forEach(s -> appendServiceRow(sb, s, serviceConfluencePageUrls));
            sb.append("</tbody>\n</table>\n");
        }

        return sb.toString();
    }

    private void appendServiceRow(StringBuilder sb, Service s, Map<UUID, String> urls) {
        String url = (urls == null || s.getId() == null) ? null : urls.get(s.getId());
        sb.append("<tr>");
        sb.append("<td>");
        if (url != null && !url.isBlank()) {
            sb.append("<a href=\"").append(escape(url)).append("\">").append(escape(s.getName())).append("</a>");
        } else {
            sb.append(escape(s.getName()));
        }
        sb.append("</td>");
        sb.append("<td>").append(escape(orDash(s.getOwnerTeam()))).append("</td>");
        sb.append("<td>").append(s.getStatus() == null ? "—" : escape(s.getStatus().name().toLowerCase())).append("</td>");
        sb.append("<td>").append(escape(orDash(s.getDescription()))).append("</td>");
        sb.append("</tr>\n");
    }

    private String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
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
