package com.atlas.confluence;

import com.atlas.services.Service;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Markdown counterpart of {@link LandingPageRenderer} (Phase 5.8 M2).
 * Output is the body of {@code README.md} at the vault root — Obsidian
 * shows it when the user opens the vault.
 *
 * <p>Service references in the index table render as Obsidian WikiLinks
 * pointing at the per-service page filename. The {@code servicePageRefs}
 * map carries the WikiLink target per service id (typically the service
 * name, since the directory layout puts the L2 page at
 * {@code services/<name>/<name>.md} so {@code [[<name>]]} resolves
 * unambiguously). When a service has no ref, its name appears as plain
 * text — same fallback the Confluence sibling uses.
 */
@Component
public class LandingMarkdownRenderer {

    private static final String PAGE_TITLE = "Atlas — Service Inventory";

    public String render(List<Service> services,
                         Map<UUID, String> servicePageRefs,
                         OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(PAGE_TITLE, "landing", lastSyncAt)));

        sb.append("# ").append(PAGE_TITLE).append("\n\n");

        sb.append("## About this space\n\n");
        sb.append("This is the Atlas service inventory. Every page below is auto-generated from the ");
        sb.append("central Atlas database via the intake → DB → wiki pipeline. **The database is the ");
        sb.append("source of truth — direct edits to any page in this vault will be overwritten on the ");
        sb.append("next sync.**\n\n");
        sb.append("**To add your service:** run the intake interview and the page will appear here on ");
        sb.append("the next sync (default cadence: every 15 minutes).\n\n");

        sb.append("### How to read this space\n\n");
        sb.append("Pages drill down from this landing page. Each service page (under *Service index* ");
        sb.append("below) carries an **Internals** section that links to four child views: ");
        sb.append("**Modules** (one page per Maven sub-module), **Code index** (a per-service Beans ");
        sb.append("page listing the Spring stereotype classes), **Tests** (a per-service test-scenarios ");
        sb.append("page), and **Endpoints** (one page per API endpoint with parameters / request / ");
        sb.append("response schemas).\n\n");

        if (lastSyncAt != null) {
            sb.append("**Last refreshed:** ").append(lastSyncAt).append("\n\n");
        }

        sb.append("## Service index\n\n");
        if (services == null || services.isEmpty()) {
            sb.append("*No services registered yet. Run intake to add the first one.*\n");
            return sb.toString();
        }

        sb.append("| Service | Owner | Status | Description |\n");
        sb.append("|---|---|---|---|\n");
        services.stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .forEach(s -> sb.append(renderServiceRow(s, servicePageRefs)));

        return sb.toString();
    }

    public static String pageTitle() {
        return PAGE_TITLE;
    }

    private String renderServiceRow(Service s, Map<UUID, String> refs) {
        String ref = (refs == null || s.getId() == null) ? null : refs.get(s.getId());
        String linkedName;
        if (ref != null && !ref.isBlank()) {
            // If the WikiLink target equals the display name, emit the
            // shorter form for cleaner reading.
            linkedName = ref.equals(s.getName())
                    ? MarkdownRenderingUtil.wikiLink(ref)
                    : MarkdownRenderingUtil.wikiLink(ref, escapePipe(s.getName()));
        } else {
            linkedName = escapePipe(s.getName());
        }
        String owner = orDash(escapePipe(s.getOwnerTeam()));
        String status = s.getStatus() == null ? "—" : s.getStatus().name().toLowerCase();
        String description = orDash(escapePipe(s.getDescription()));
        return "| " + linkedName + " | " + owner + " | " + status + " | " + description + " |\n";
    }

    private String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /**
     * GFM pipe tables use {@code |} as the column delimiter; cell content
     * containing a literal pipe must escape it with a backslash.
     */
    private String escapePipe(String s) {
        if (s == null) return null;
        return s.replace("|", "\\|");
    }
}
