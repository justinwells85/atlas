package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ServicePageContext} as a Confluence storage-format string —
 * the seven-section page template documented in {@code docs/confluence-template.md}.
 *
 * Pure function: no I/O, no Confluence client, no DB. Missing relationship data
 * renders as a thin "No X documented yet." note rather than an empty section or
 * an error, so pages stay structurally consistent regardless of intake completeness.
 */
@Component
public class ServicePageRenderer {

    public String render(ServicePageContext ctx) {
        StringBuilder sb = new StringBuilder();
        renderOverview(sb, ctx);
        renderTechnicalDetails(sb, ctx);
        renderApis(sb, ctx);
        renderDependencies(sb, ctx);
        renderData(sb, ctx);
        renderOperational(sb, ctx);
        renderChangeHistory(sb, ctx);
        return sb.toString();
    }

    // ---- Sections ---------------------------------------------------------

    private void renderOverview(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("<h2>Overview</h2>\n");
        if (hasText(s.getDescription())) {
            sb.append("<p>").append(escape(s.getDescription())).append("</p>\n");
        }
        appendField(sb, "Owner", s.getOwnerTeam());
        appendField(sb, "Status", statusLabel(s.getStatus()));
    }

    private void renderTechnicalDetails(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("<h2>Technical Details</h2>\n");
        appendField(sb, "Language", s.getLanguage());
        appendField(sb, "Framework", s.getFramework());
        if (hasText(s.getRepoUrl())) {
            sb.append("<p><strong>Repository:</strong> ")
                    .append(renderLink(s.getRepoUrl(), s.getRepoUrl()))
                    .append("</p>\n");
        }
        appendField(sb, "Deployment", s.getDeployment());
    }

    private void renderApis(StringBuilder sb, ServicePageContext ctx) {
        sb.append("<h2>APIs</h2>\n");
        if (ctx.apis().isEmpty()) {
            appendThinNote(sb, "No APIs documented yet.");
            return;
        }
        sb.append("<ul>\n");
        for (ApiPresentation pres : ctx.apis()) {
            sb.append("<li><strong>")
                    .append(escape(pres.api().method())).append(" ")
                    .append(escape(pres.api().path()))
                    .append("</strong>");
            if (hasText(pres.api().description())) {
                sb.append(" — ").append(escape(pres.api().description()));
            }
            if (hasText(pres.api().authMethod())) {
                sb.append(" (auth: ").append(escape(pres.api().authMethod())).append(")");
            }
            if (!pres.consumers().isEmpty()) {
                sb.append("<br/>Consumers: ").append(joinConsumerNames(pres.consumers()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private void renderDependencies(StringBuilder sb, ServicePageContext ctx) {
        sb.append("<h2>Dependencies</h2>\n");

        sb.append("<h3>Databases</h3>\n");
        if (ctx.databases().isEmpty()) {
            appendThinNote(sb, "No databases documented yet.");
        } else {
            sb.append("<ul>\n");
            for (DatabaseUsage db : ctx.databases()) {
                sb.append("<li><strong>").append(escape(db.databaseName())).append("</strong>");
                if (hasText(db.engine())) {
                    sb.append(" (").append(escape(db.engine())).append(")");
                }
                if (db.isOwner()) {
                    sb.append(" — owned");
                }
                if (hasText(db.description())) {
                    sb.append(" — ").append(escape(db.description()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h3>Upstream Services</h3>\n");
        if (ctx.upstreamServices().isEmpty()) {
            appendThinNote(sb, "No upstream services documented yet.");
        } else {
            sb.append("<ul>\n");
            for (ServiceDependencyEdge edge : ctx.upstreamServices()) {
                sb.append("<li><strong>").append(escape(edge.upstreamServiceName())).append("</strong>");
                if (hasText(edge.description())) {
                    sb.append(" — ").append(escape(edge.description()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h3>Downstream Services</h3>\n");
        if (ctx.downstreamServices().isEmpty()) {
            appendThinNote(sb, "No downstream services documented yet.");
        } else {
            sb.append("<ul>\n");
            for (ServiceDependencyEdge edge : ctx.downstreamServices()) {
                sb.append("<li><strong>").append(escape(edge.downstreamServiceName())).append("</strong>");
                if (hasText(edge.description())) {
                    sb.append(" — ").append(escape(edge.description()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h3>External Dependencies</h3>\n");
        if (ctx.externalDependencies().isEmpty()) {
            appendThinNote(sb, "No external dependencies documented yet.");
        } else {
            sb.append("<ul>\n");
            for (ExternalDependencyUsage ext : ctx.externalDependencies()) {
                sb.append("<li><strong>");
                if (hasText(ext.url())) {
                    sb.append(renderLink(ext.url(), ext.name()));
                } else {
                    sb.append(escape(ext.name()));
                }
                sb.append("</strong>");
                if (hasText(ext.description())) {
                    sb.append(" — ").append(escape(ext.description()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }
    }

    private void renderData(StringBuilder sb, ServicePageContext ctx) {
        sb.append("<h2>Data</h2>\n");

        sb.append("<h3>Owned Databases</h3>\n");
        List<DatabaseUsage> owned = ctx.databases().stream()
                .filter(DatabaseUsage::isOwner)
                .toList();
        if (owned.isEmpty()) {
            appendThinNote(sb, "No owned databases documented yet.");
        } else {
            sb.append("<ul>\n");
            for (DatabaseUsage db : owned) {
                sb.append("<li><strong>").append(escape(db.databaseName())).append("</strong>");
                if (hasText(db.engine())) {
                    sb.append(" (").append(escape(db.engine())).append(")");
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h3>Data Classification</h3>\n");
        Map<String, Object> meta = ctx.service().getMetadata();
        Object classification = meta == null ? null : meta.get("data_classification");
        if (classification == null || !hasText(classification.toString())) {
            appendThinNote(sb, "No data classification documented yet.");
        } else {
            sb.append("<p>").append(escape(classification.toString())).append("</p>\n");
        }
    }

    private void renderOperational(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("<h2>Operational</h2>\n");
        appendField(sb, "Support Contact", s.getSupportContact());
        appendField(sb, "SLA", s.getSla());
        if (hasText(s.getNotes())) {
            sb.append("<p><strong>Notes:</strong> ").append(escape(s.getNotes())).append("</p>\n");
        }
    }

    private void renderChangeHistory(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("<h2>Change History</h2>\n");
        OffsetDateTime updated = s.getUpdatedAt();
        if (updated != null) {
            sb.append("<p><strong>Last Updated:</strong> ")
                    .append(escape(updated.toString()))
                    .append("</p>\n");
        }
        if (ctx.recentChanges().isEmpty()) {
            appendThinNote(sb, "No recent changes recorded.");
            return;
        }
        sb.append("<h3>Recent Changes</h3>\n<ul>\n");
        for (ChangeEntry c : ctx.recentChanges()) {
            sb.append("<li>");
            if (c.changedAt() != null) {
                sb.append(escape(c.changedAt().toString())).append(" — ");
            }
            if (hasText(c.changedBy())) {
                sb.append(escape(c.changedBy())).append(" — ");
            }
            if (hasText(c.changeType())) {
                sb.append(escape(c.changeType())).append(" — ");
            }
            if (hasText(c.summary())) {
                sb.append(escape(c.summary()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    // ---- Helpers ---------------------------------------------------------

    private void appendField(StringBuilder sb, String label, String value) {
        if (!hasText(value)) {
            return;
        }
        sb.append("<p><strong>").append(label).append(":</strong> ")
                .append(escape(value)).append("</p>\n");
    }

    private void appendThinNote(StringBuilder sb, String message) {
        sb.append("<p><em>").append(escape(message)).append("</em></p>\n");
    }

    private String renderLink(String href, String text) {
        return "<a href=\"" + escape(href) + "\">" + escape(text) + "</a>";
    }

    private String joinConsumerNames(List<ApiConsumer> consumers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < consumers.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(escape(consumers.get(i).consumerServiceName()));
        }
        return sb.toString();
    }

    private String statusLabel(ServiceStatus status) {
        return status == null ? null : status.name().toLowerCase();
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
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
