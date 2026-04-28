package com.atlas.confluence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Renders the "About Atlas" page — static-ish content explaining what this
 * space is, who maintains it, and how to add a service. Sits alongside the
 * landing page and inventory pages under the Atlas Confluence space root.
 *
 * The body is fully self-contained: the only dynamic element is the
 * last-refreshed timestamp. Static copy is wired here rather than in a
 * resource file so it travels with the renderer's tests.
 */
@Component
public class AboutPageRenderer {

    private final String repoUrl;
    private final String intakeBaseUrl;
    private final String mcpBaseUrl;
    private final String syncBaseUrl;

    public AboutPageRenderer(
            @Value("${atlas.about.repo-url:https://github.com/justinwells85/atlas}") String repoUrl,
            @Value("${atlas.about.intake-url:http://127.0.0.1:8080}") String intakeBaseUrl,
            @Value("${atlas.about.mcp-url:http://127.0.0.1:8081}") String mcpBaseUrl,
            @Value("${atlas.about.sync-url:http://127.0.0.1:8082}") String syncBaseUrl) {
        this.repoUrl = repoUrl;
        this.intakeBaseUrl = intakeBaseUrl;
        this.mcpBaseUrl = mcpBaseUrl;
        this.syncBaseUrl = syncBaseUrl;
    }

    public String render(OffsetDateTime lastSyncAt) {
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>What is Atlas?</h2>\n");
        sb.append("<p>Atlas is an AI-maintained service-documentation system. It interviews service " +
                "owners, captures the structure of their service (APIs, dependencies, owners, databases), " +
                "stores the inventory in a central database, and auto-generates the Confluence pages " +
                "you see in this space. <strong>The Atlas database is the source of truth — direct " +
                "edits to any page in this space will be overwritten on the next sync.</strong></p>\n");

        sb.append("<h2>How to add your service</h2>\n");
        sb.append("<p>Run the Atlas intake interview. The interview takes ~10 minutes of structured " +
                "questions; once you complete it, your service appears in the inventory database and " +
                "gets a Confluence page on the next scheduled sync (default: every 15 minutes).</p>\n");
        sb.append("<p>The intake REST endpoint is <code>POST /api/intake/turn</code> on the atlas-intake " +
                "service.</p>\n");

        sb.append("<h2>Architecture</h2>\n");
        sb.append("<p>Atlas is three Spring Boot services plus a shared library:</p>\n");
        sb.append("<ul>\n");
        sb.append("<li><strong>atlas-intake</strong> — REST service that conducts the AI-assisted " +
                "interview and persists captured data.</li>\n");
        sb.append("<li><strong>atlas-mcp</strong> — MCP (Model Context Protocol) server exposing the " +
                "inventory data over HTTP/SSE; readable by Claude Desktop, Claude Code, and other MCP " +
                "clients.</li>\n");
        sb.append("<li><strong>atlas-confluence-sync</strong> — The agent that renders the inventory " +
                "into the Confluence pages you're reading right now. Runs on a schedule and on demand.</li>\n");
        sb.append("<li><strong>atlas-domain</strong> — Shared library with JPA entities and Flyway " +
                "migrations.</li>\n");
        sb.append("</ul>\n");

        sb.append("<h2>Local endpoints (prototype)</h2>\n");
        sb.append("<ul>\n");
        sb.append("<li>atlas-intake: ").append(renderLink(intakeBaseUrl, intakeBaseUrl)).append("</li>\n");
        sb.append("<li>atlas-mcp: ").append(renderLink(mcpBaseUrl, mcpBaseUrl)).append("</li>\n");
        sb.append("<li>atlas-confluence-sync: ").append(renderLink(syncBaseUrl, syncBaseUrl)).append("</li>\n");
        sb.append("</ul>\n");
        sb.append("<p><em>These are prototype 127.0.0.1-only addresses. Production deployment is " +
                "covered in the AWS migration plan in the source repo.</em></p>\n");

        sb.append("<h2>Source code &amp; design docs</h2>\n");
        sb.append("<ul>\n");
        sb.append("<li>Repository: ").append(renderLink(repoUrl, repoUrl)).append("</li>\n");
        sb.append("<li>Architecture: ").append(renderLink(repoUrl + "/blob/main/docs/architecture.md", "docs/architecture.md")).append("</li>\n");
        sb.append("<li>Decisions (ADRs): ").append(renderLink(repoUrl + "/blob/main/docs/decisions.md", "docs/decisions.md")).append("</li>\n");
        sb.append("<li>Deferred decisions: ").append(renderLink(repoUrl + "/blob/main/docs/deferred-decisions.md", "docs/deferred-decisions.md")).append("</li>\n");
        sb.append("<li>Confluence layout proposal: ").append(renderLink(repoUrl + "/blob/main/docs/confluence-layout.md", "docs/confluence-layout.md")).append("</li>\n");
        sb.append("</ul>\n");

        if (lastSyncAt != null) {
            sb.append("<p><strong>Last refreshed:</strong> ").append(escape(lastSyncAt.toString())).append("</p>\n");
        }

        return sb.toString();
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
