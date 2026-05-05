package com.atlas.confluence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Markdown counterpart of {@link AboutPageRenderer} (Phase 5.8 M2).
 * Emits GitHub-Flavored Markdown with Obsidian-friendly YAML front matter.
 * The output is the body of {@code about.md} in the local-markdown vault.
 *
 * <p>The renderer is body-format-only — no file-system or sink interaction.
 * The {@link LocalMarkdownWikiSink} (M3) is what actually writes the file.
 */
@Component
public class AboutMarkdownRenderer {

    private static final String PAGE_TITLE = "About Atlas";

    private final String repoUrl;
    private final String intakeBaseUrl;
    private final String mcpBaseUrl;
    private final String syncBaseUrl;

    public AboutMarkdownRenderer(
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

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(PAGE_TITLE, "about", lastSyncAt)));

        sb.append("# ").append(PAGE_TITLE).append("\n\n");

        sb.append("## What is Atlas?\n\n");
        sb.append("Atlas is an AI-maintained service-documentation system. It interviews service owners, ");
        sb.append("captures the structure of their service (APIs, dependencies, owners, databases), stores ");
        sb.append("the inventory in a central database, and auto-generates the wiki pages you see in this ");
        sb.append("vault. **The Atlas database is the source of truth — direct edits to any page in this ");
        sb.append("vault will be overwritten on the next sync.**\n\n");

        sb.append("## How to add your service\n\n");
        sb.append("Run the Atlas intake interview. The interview takes ~10 minutes of structured questions; ");
        sb.append("once you complete it, your service appears in the inventory database and gets a page in ");
        sb.append("this vault on the next scheduled sync (default: every 15 minutes).\n\n");
        sb.append("The intake REST endpoint is `POST /api/intake/turn` on the atlas-intake service.\n\n");

        sb.append("## Architecture\n\n");
        sb.append("Atlas is three Spring Boot services plus a shared library:\n\n");
        sb.append("- **atlas-intake** — REST service that conducts the AI-assisted interview and persists captured data.\n");
        sb.append("- **atlas-mcp** — MCP (Model Context Protocol) server exposing the inventory data over HTTP/SSE; ");
        sb.append("readable by Claude Desktop, Claude Code, and other MCP clients.\n");
        sb.append("- **atlas-confluence-sync** — The agent that renders the inventory into the wiki pages you're ");
        sb.append("reading right now. Runs on a schedule and on demand.\n");
        sb.append("- **atlas-domain** — Shared library with JPA entities and Flyway migrations.\n\n");

        sb.append("## Local endpoints (prototype)\n\n");
        sb.append("- atlas-intake: [").append(intakeBaseUrl).append("](").append(intakeBaseUrl).append(")\n");
        sb.append("- atlas-mcp: [").append(mcpBaseUrl).append("](").append(mcpBaseUrl).append(")\n");
        sb.append("- atlas-confluence-sync: [").append(syncBaseUrl).append("](").append(syncBaseUrl).append(")\n\n");
        sb.append("*These are prototype 127.0.0.1-only addresses. Production deployment is covered in the AWS ");
        sb.append("migration plan in the source repo.*\n\n");

        sb.append("## Source code & design docs\n\n");
        sb.append("- Repository: [").append(repoUrl).append("](").append(repoUrl).append(")\n");
        sb.append("- Architecture: [docs/architecture.md](").append(repoUrl).append("/blob/main/docs/architecture.md)\n");
        sb.append("- Decisions (ADRs): [docs/decisions.md](").append(repoUrl).append("/blob/main/docs/decisions.md)\n");
        sb.append("- Deferred decisions: [docs/deferred-decisions.md](").append(repoUrl).append("/blob/main/docs/deferred-decisions.md)\n");
        sb.append("- Confluence layout proposal: [docs/confluence-layout.md](").append(repoUrl).append("/blob/main/docs/confluence-layout.md)\n");

        if (lastSyncAt != null) {
            sb.append('\n');
            sb.append("**Last refreshed:** ").append(lastSyncAt).append('\n');
        }

        return sb.toString();
    }

    public static String pageTitle() {
        return PAGE_TITLE;
    }
}
