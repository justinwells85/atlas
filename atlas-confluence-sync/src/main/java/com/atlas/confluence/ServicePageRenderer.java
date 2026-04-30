package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceModule;
import com.atlas.services.ServiceStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        renderInternals(sb, ctx);
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

        // M4.5: prefer service_metadata observations over the legacy entity
        // columns. Among observations for the same key, pom-xml wins over
        // other sources (today only pom-xml writes here; intake-source
        // observations land via M5).
        Map<String, ServiceMetadata> meta = resolveMetadataByKey(ctx.serviceMetadata());
        ServiceMetadata languageObs = meta.get("language");
        ServiceMetadata frameworkObs = meta.get("framework");
        ServiceMetadata languageVersionObs = meta.get("language_version");
        ServiceMetadata frameworkVersionObs = meta.get("framework_version");
        ServiceMetadata buildToolObs = meta.get("build_tool");

        String language = languageObs != null ? languageObs.value() : s.getLanguage();
        String framework = frameworkObs != null ? frameworkObs.value() : s.getFramework();
        String languageVersion = languageVersionObs != null ? languageVersionObs.value() : null;
        String frameworkVersion = frameworkVersionObs != null ? frameworkVersionObs.value() : null;
        String buildTool = buildToolObs != null ? buildToolObs.value() : null;

        if (!hasText(language) && !hasText(framework)
                && !hasText(s.getRepoUrl()) && !hasText(s.getDeployment())
                && !hasText(languageVersion) && !hasText(frameworkVersion)
                && !hasText(buildTool)) {
            appendThinNote(sb, "No technical details documented yet.");
            return;
        }
        appendFieldWithSource(sb, "Language", language, sourceOf(languageObs));
        appendFieldWithSource(sb, "Language Version", languageVersion, sourceOf(languageVersionObs));
        appendFieldWithSource(sb, "Framework", framework, sourceOf(frameworkObs));
        appendFieldWithSource(sb, "Framework Version", frameworkVersion, sourceOf(frameworkVersionObs));
        appendFieldWithSource(sb, "Build Tool", buildTool, sourceOf(buildToolObs));
        if (hasText(s.getRepoUrl())) {
            sb.append("<p><strong>Repository:</strong> ")
                    .append(renderLink(s.getRepoUrl(), s.getRepoUrl()))
                    .append("</p>\n");
        }
        appendField(sb, "Deployment", s.getDeployment());
    }

    /**
     * Pick one observation per key, preferring {@code pom-xml} over other
     * sources when multiple exist for the same key. (Today only pom-xml
     * writes service_metadata; intake-source observations are an M5 plan.)
     */
    private static Map<String, ServiceMetadata> resolveMetadataByKey(List<ServiceMetadata> all) {
        Map<String, ServiceMetadata> out = new HashMap<>();
        for (ServiceMetadata m : all) {
            ServiceMetadata cur = out.get(m.key());
            if (cur == null || ("pom-xml".equals(m.source()) && !"pom-xml".equals(cur.source()))) {
                out.put(m.key(), m);
            }
        }
        return out;
    }

    private static String sourceOf(ServiceMetadata m) {
        return m == null ? null : m.source();
    }

    private void appendFieldWithSource(StringBuilder sb, String label, String value, String source) {
        if (!hasText(value)) {
            return;
        }
        sb.append("<p><strong>").append(label).append(":</strong> ")
                .append(escape(value));
        if ("pom-xml".equals(source)) {
            sb.append(" <em>(from pom.xml)</em>");
        }
        sb.append("</p>\n");
    }

    private void renderApis(StringBuilder sb, ServicePageContext ctx) {
        sb.append("<h2>APIs</h2>\n");
        if (ctx.apis().isEmpty()) {
            appendThinNote(sb, "No APIs documented yet.");
            return;
        }
        sb.append("<ul>\n");
        for (ApiPresentation pres : ctx.apis()) {
            sb.append("<li><strong>");
            String label = pres.api().method() + " " + pres.api().path();
            if (hasText(pres.endpointPageUrl())) {
                sb.append(renderLink(pres.endpointPageUrl(), label));
            } else {
                sb.append(escape(label));
            }
            sb.append("</strong>");
            if (hasText(pres.api().description())) {
                sb.append(" — ").append(escape(pres.api().description()));
            }
            if (hasText(pres.api().authMethod())) {
                sb.append(" (auth: ").append(escape(pres.api().authMethod())).append(")");
            }
            if (!pres.consumers().isEmpty()) {
                sb.append("<br/>Consumers: ").append(joinConsumerNames(pres.consumers(), ctx.serviceConfluencePageUrls()));
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
            String dsInventoryUrl = ctx.inventoryPageUrls() == null ? null : ctx.inventoryPageUrls().dataStores();
            sb.append("<ul>\n");
            for (DatabaseUsage db : ctx.databases()) {
                sb.append("<li><strong>");
                if (dsInventoryUrl != null && !dsInventoryUrl.isBlank()) {
                    sb.append(renderLink(dsInventoryUrl, db.databaseName()));
                } else {
                    sb.append(escape(db.databaseName()));
                }
                sb.append("</strong>");
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
                sb.append("<li><strong>")
                        .append(renderServiceLink(edge.upstreamServiceId(), edge.upstreamServiceName(), ctx.serviceConfluencePageUrls()))
                        .append("</strong>");
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
                sb.append("<li><strong>")
                        .append(renderServiceLink(edge.downstreamServiceId(), edge.downstreamServiceName(), ctx.serviceConfluencePageUrls()))
                        .append("</strong>");
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
            renderExternalDependencies(sb, ctx);
        }
    }

    /**
     * Render the External Dependencies bullet list, composing intake-source
     * rows (carry human descriptions) with pom-source rows (carry
     * {@code groupId:artifactId} coordinates). When a pom-source row matches
     * an intake-source row by artifactId substring, the pom row collapses
     * into a "matches pom: ..." annotation on the intake row. Unmatched
     * pom-source rows render standalone with a "(from pom.xml)" suffix.
     */
    private void renderExternalDependencies(StringBuilder sb, ServicePageContext ctx) {
        String edInventoryUrl = ctx.inventoryPageUrls() == null ? null
                : ctx.inventoryPageUrls().externalDependencies();
        List<ExternalDependencyUsage> all = ctx.externalDependencies();

        List<ExternalDependencyUsage> intakeRows = new ArrayList<>();
        List<ExternalDependencyUsage> pomRows = new ArrayList<>();
        List<ExternalDependencyUsage> otherRows = new ArrayList<>();
        for (ExternalDependencyUsage ext : all) {
            if ("pom-xml".equals(ext.source())) {
                pomRows.add(ext);
            } else if ("intake".equals(ext.source()) || ext.source() == null) {
                intakeRows.add(ext);
            } else {
                otherRows.add(ext);
            }
        }

        Map<UUID, String> annotationsByIntakeId = new LinkedHashMap<>();
        Set<UUID> matchedPomIds = new HashSet<>();
        for (ExternalDependencyUsage pom : pomRows) {
            ExternalDependencyUsage matched = matchIntakeRow(pom.name(), intakeRows);
            if (matched != null) {
                annotationsByIntakeId.put(matched.externalDependencyId(), pom.name());
                matchedPomIds.add(pom.externalDependencyId());
            }
        }

        sb.append("<ul>\n");
        for (ExternalDependencyUsage ext : intakeRows) {
            sb.append("<li><strong>");
            if (hasText(ext.url())) {
                sb.append(renderLink(ext.url(), ext.name()));
            } else {
                sb.append(escape(ext.name()));
            }
            sb.append("</strong>");
            if (edInventoryUrl != null && !edInventoryUrl.isBlank()) {
                sb.append(" (").append(renderLink(edInventoryUrl, "in inventory")).append(")");
            }
            if (hasText(ext.description())) {
                sb.append(" — ").append(escape(ext.description()));
            }
            String pomCoords = annotationsByIntakeId.get(ext.externalDependencyId());
            if (pomCoords != null) {
                sb.append(" (✓ matches pom: ").append(escape(pomCoords)).append(")");
            }
            sb.append("</li>\n");
        }
        for (ExternalDependencyUsage pom : pomRows) {
            if (matchedPomIds.contains(pom.externalDependencyId())) {
                continue;
            }
            sb.append("<li><strong>").append(escape(pom.name())).append("</strong>");
            sb.append(" <em>(from pom.xml)</em>");
            sb.append("</li>\n");
        }
        for (ExternalDependencyUsage other : otherRows) {
            sb.append("<li><strong>");
            if (hasText(other.url())) {
                sb.append(renderLink(other.url(), other.name()));
            } else {
                sb.append(escape(other.name()));
            }
            sb.append("</strong>");
            if (hasText(other.description())) {
                sb.append(" — ").append(escape(other.description()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    /**
     * Match a {@code groupId:artifactId} pom-source name against intake-source
     * rows: split the artifactId into hyphen-separated tokens, keep tokens of
     * length ≥ 4, and match if any token is a case-insensitive substring of
     * any intake row's name. Conservative — short tokens like "api" or "lib"
     * don't trigger spurious merges.
     */
    private static ExternalDependencyUsage matchIntakeRow(String pomName,
                                                          List<ExternalDependencyUsage> intakeRows) {
        if (pomName == null) return null;
        int colon = pomName.indexOf(':');
        if (colon < 0) return null;
        String artifactId = pomName.substring(colon + 1).toLowerCase();
        String[] tokens = artifactId.split("-");
        for (String token : tokens) {
            if (token.length() < 4) continue;
            for (ExternalDependencyUsage intake : intakeRows) {
                if (intake.name() != null && intake.name().toLowerCase().contains(token)) {
                    return intake;
                }
            }
        }
        return null;
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
        if (!hasText(s.getSupportContact()) && !hasText(s.getSla()) && !hasText(s.getNotes())) {
            appendThinNote(sb, "No operational details documented yet.");
            return;
        }
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

    /**
     * Section 8 "Internals" — the L1→L5 drill-down cross-reference block
     * (Phase 5.6 M4). Four sub-bullets: Modules (L4), Code index (L5 Beans),
     * Tests (per-service Tests page), Endpoints (recap of L3 endpoint pages).
     * When a layer has no data, the bullet renders a thin note instead of
     * disappearing — same convention as the rest of the template.
     */
    private void renderInternals(StringBuilder sb, ServicePageContext ctx) {
        sb.append("<h2>Internals</h2>\n");

        sb.append("<h3>Modules</h3>\n");
        List<ServiceModule> modules = ctx.modules();
        Map<String, String> moduleUrls = ctx.modulePageUrlsByPath();
        if (modules == null || modules.isEmpty()) {
            appendThinNote(sb, "No modules documented yet.");
        } else {
            sb.append("<ul>\n");
            for (ServiceModule m : modules) {
                String path = hasText(m.modulePath()) ? m.modulePath() : "(root)";
                String url = moduleUrls == null ? null : moduleUrls.get(m.modulePath());
                sb.append("<li>");
                if (hasText(url)) {
                    sb.append(renderLink(url, path));
                } else {
                    sb.append(escape(path));
                }
                if (hasText(m.artifactId())) {
                    sb.append(" — ").append(escape(coordinatesSummary(m)));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("<h3>Code index</h3>\n");
        if (hasText(ctx.beansPageUrl())) {
            sb.append("<p>")
                    .append(renderLink(ctx.beansPageUrl(), "Beans (Spring stereotype classes)"))
                    .append("</p>\n");
        } else {
            appendThinNote(sb, "No code index documented yet.");
        }

        sb.append("<h3>Tests</h3>\n");
        if (hasText(ctx.testsPageUrl())) {
            sb.append("<p>")
                    .append(renderLink(ctx.testsPageUrl(), "Test scenarios"))
                    .append("</p>\n");
        } else {
            appendThinNote(sb, "No tests documented yet.");
        }

        sb.append("<h3>Endpoints</h3>\n");
        List<ApiPresentation> endpointsWithUrls = ctx.apis().stream()
                .filter(p -> hasText(p.endpointPageUrl()))
                .toList();
        if (endpointsWithUrls.isEmpty()) {
            appendThinNote(sb, "No endpoints documented yet.");
        } else {
            sb.append("<ul>\n");
            for (ApiPresentation pres : endpointsWithUrls) {
                String label = pres.api().method() + " " + pres.api().path();
                sb.append("<li>")
                        .append(renderLink(pres.endpointPageUrl(), label))
                        .append("</li>\n");
            }
            sb.append("</ul>\n");
        }
    }

    private static String coordinatesSummary(ServiceModule m) {
        StringBuilder cs = new StringBuilder();
        if (m.groupId() != null && !m.groupId().isBlank()) {
            cs.append(m.groupId()).append(":");
        }
        cs.append(m.artifactId() == null ? "" : m.artifactId());
        if (m.version() != null && !m.version().isBlank()) {
            cs.append(":").append(m.version());
        }
        return cs.toString();
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

    private String joinConsumerNames(List<ApiConsumer> consumers, Map<UUID, String> urls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < consumers.size(); i++) {
            if (i > 0) sb.append(", ");
            ApiConsumer c = consumers.get(i);
            sb.append(renderServiceLink(c.consumerServiceId(), c.consumerServiceName(), urls));
        }
        return sb.toString();
    }

    /**
     * If the referenced service has a Confluence page URL on the context map,
     * render its name as a hyperlink. Otherwise (peer not yet synced, or map
     * unavailable), fall back to plain text — readers still see the name,
     * just not clickable. Eventual consistency: the next sync round picks up
     * the link once the peer page exists.
     */
    private String renderServiceLink(UUID serviceId, String name, Map<UUID, String> urls) {
        String url = (urls == null || serviceId == null) ? null : urls.get(serviceId);
        if (url == null || url.isBlank()) {
            return escape(name);
        }
        return renderLink(url, name);
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
