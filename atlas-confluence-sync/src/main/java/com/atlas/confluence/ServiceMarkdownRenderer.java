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
 * Markdown counterpart of {@link ServicePageRenderer} (Phase 5.8 M2).
 *
 * <p>Eight sections, mirroring the Confluence sibling: Overview, Technical
 * Details, APIs, Dependencies (databases / upstream / downstream / external),
 * Data, Operational, Change History, Internals (Modules / Code index /
 * Tests / Endpoints — the L1→L5 drill-down cross-reference block from
 * Phase 5.6 M4).
 *
 * <p>Service-to-service references render as Obsidian WikiLinks; URL maps
 * carried in {@link ServicePageContext} contain WikiLink targets when this
 * renderer is invoked from the Markdown sink. The {@code endpointPageUrl}
 * on {@link ApiPresentation} likewise carries a WikiLink target.
 */
@Component
public class ServiceMarkdownRenderer {

    public String render(ServicePageContext ctx) {
        Service s = ctx.service();
        String title = "Service: " + s.getName();

        StringBuilder sb = new StringBuilder();
        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(title, "service", null)));

        sb.append("# ").append(title).append("\n\n");

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

    public static String pageTitle(Service service) {
        return "Service: " + service.getName();
    }

    // ---- Sections ---------------------------------------------------------

    private void renderOverview(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("## Overview\n\n");
        if (hasText(s.getDescription())) {
            sb.append(s.getDescription()).append("\n\n");
        }
        appendField(sb, "Owner", s.getOwnerTeam());
        appendField(sb, "Status", statusLabel(s.getStatus()));
        sb.append('\n');
    }

    private void renderTechnicalDetails(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("## Technical Details\n\n");

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
            sb.append("**Repository:** [").append(s.getRepoUrl()).append("](")
                    .append(s.getRepoUrl()).append(")\n");
        }
        appendField(sb, "Deployment", s.getDeployment());
        sb.append('\n');
    }

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
        if (!hasText(value)) return;
        sb.append("**").append(label).append(":** ").append(value);
        if ("pom-xml".equals(source)) {
            sb.append(" *(from pom.xml)*");
        }
        sb.append('\n');
    }

    private void renderApis(StringBuilder sb, ServicePageContext ctx) {
        sb.append("## APIs\n\n");
        if (ctx.apis().isEmpty()) {
            appendThinNote(sb, "No APIs documented yet.");
            sb.append('\n');
            return;
        }
        for (ApiPresentation pres : ctx.apis()) {
            String label = pres.api().method() + " " + pres.api().path();
            sb.append("- **");
            if (hasText(pres.endpointPageUrl())) {
                sb.append(MarkdownRenderingUtil.wikiLink(pres.endpointPageUrl(), label));
            } else {
                sb.append(label);
            }
            sb.append("**");
            if (hasText(pres.api().description())) {
                sb.append(" — ").append(pres.api().description());
            }
            if (hasText(pres.api().authMethod())) {
                sb.append(" (auth: ").append(pres.api().authMethod()).append(')');
            }
            if (!pres.consumers().isEmpty()) {
                sb.append("  \n  Consumers: ")
                        .append(joinConsumerNames(pres.consumers(), ctx.serviceConfluencePageUrls()));
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void renderDependencies(StringBuilder sb, ServicePageContext ctx) {
        sb.append("## Dependencies\n\n");

        sb.append("### Databases\n\n");
        if (ctx.databases().isEmpty()) {
            appendThinNote(sb, "No databases documented yet.");
        } else {
            String dsInventoryRef = ctx.inventoryPageUrls() == null ? null
                    : ctx.inventoryPageUrls().dataStores();
            for (DatabaseUsage db : ctx.databases()) {
                sb.append("- **");
                if (dsInventoryRef != null && !dsInventoryRef.isBlank()) {
                    sb.append(MarkdownRenderingUtil.wikiLink(dsInventoryRef, db.databaseName()));
                } else {
                    sb.append(db.databaseName());
                }
                sb.append("**");
                if (hasText(db.engine())) {
                    sb.append(" (").append(db.engine()).append(')');
                }
                if (db.isOwner()) {
                    sb.append(" — owned");
                }
                if (hasText(db.description())) {
                    sb.append(" — ").append(db.description());
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("### Upstream Services\n\n");
        if (ctx.upstreamServices().isEmpty()) {
            appendThinNote(sb, "No upstream services documented yet.");
        } else {
            for (ServiceDependencyEdge edge : ctx.upstreamServices()) {
                sb.append("- **").append(renderServiceLink(edge.upstreamServiceId(),
                                edge.upstreamServiceName(), ctx.serviceConfluencePageUrls()))
                        .append("**");
                if (hasText(edge.description())) {
                    sb.append(" — ").append(edge.description());
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("### Downstream Services\n\n");
        if (ctx.downstreamServices().isEmpty()) {
            appendThinNote(sb, "No downstream services documented yet.");
        } else {
            for (ServiceDependencyEdge edge : ctx.downstreamServices()) {
                sb.append("- **").append(renderServiceLink(edge.downstreamServiceId(),
                                edge.downstreamServiceName(), ctx.serviceConfluencePageUrls()))
                        .append("**");
                if (hasText(edge.description())) {
                    sb.append(" — ").append(edge.description());
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("### External Dependencies\n\n");
        if (ctx.externalDependencies().isEmpty()) {
            appendThinNote(sb, "No external dependencies documented yet.");
            sb.append('\n');
            return;
        }
        renderExternalDependencies(sb, ctx);
    }

    private void renderExternalDependencies(StringBuilder sb, ServicePageContext ctx) {
        String edInventoryRef = ctx.inventoryPageUrls() == null ? null
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

        for (ExternalDependencyUsage ext : intakeRows) {
            sb.append("- **");
            if (hasText(ext.url())) {
                sb.append('[').append(ext.name()).append("](").append(ext.url()).append(')');
            } else {
                sb.append(ext.name());
            }
            sb.append("**");
            if (edInventoryRef != null && !edInventoryRef.isBlank()) {
                sb.append(" (").append(MarkdownRenderingUtil.wikiLink(edInventoryRef, "in inventory")).append(')');
            }
            if (hasText(ext.description())) {
                sb.append(" — ").append(ext.description());
            }
            String pomCoords = annotationsByIntakeId.get(ext.externalDependencyId());
            if (pomCoords != null) {
                sb.append(" (✓ matches pom: ").append(pomCoords).append(')');
            }
            sb.append('\n');
        }
        for (ExternalDependencyUsage pom : pomRows) {
            if (matchedPomIds.contains(pom.externalDependencyId())) continue;
            sb.append("- **").append(pom.name()).append("** *(from pom.xml)*\n");
        }
        for (ExternalDependencyUsage other : otherRows) {
            sb.append("- **");
            if (hasText(other.url())) {
                sb.append('[').append(other.name()).append("](").append(other.url()).append(')');
            } else {
                sb.append(other.name());
            }
            sb.append("**");
            if (hasText(other.description())) {
                sb.append(" — ").append(other.description());
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

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
        sb.append("## Data\n\n");

        sb.append("### Owned Databases\n\n");
        List<DatabaseUsage> owned = ctx.databases().stream()
                .filter(DatabaseUsage::isOwner)
                .toList();
        if (owned.isEmpty()) {
            appendThinNote(sb, "No owned databases documented yet.");
        } else {
            for (DatabaseUsage db : owned) {
                sb.append("- **").append(db.databaseName()).append("**");
                if (hasText(db.engine())) {
                    sb.append(" (").append(db.engine()).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("### Data Classification\n\n");
        Map<String, Object> meta = ctx.service().getMetadata();
        Object classification = meta == null ? null : meta.get("data_classification");
        if (classification == null || !hasText(classification.toString())) {
            appendThinNote(sb, "No data classification documented yet.");
        } else {
            sb.append(classification.toString()).append('\n');
        }
        sb.append('\n');
    }

    private void renderOperational(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("## Operational\n\n");
        if (!hasText(s.getSupportContact()) && !hasText(s.getSla()) && !hasText(s.getNotes())) {
            appendThinNote(sb, "No operational details documented yet.");
            sb.append('\n');
            return;
        }
        appendField(sb, "Support Contact", s.getSupportContact());
        appendField(sb, "SLA", s.getSla());
        if (hasText(s.getNotes())) {
            sb.append("**Notes:** ").append(s.getNotes()).append('\n');
        }
        sb.append('\n');
    }

    private void renderChangeHistory(StringBuilder sb, ServicePageContext ctx) {
        Service s = ctx.service();
        sb.append("## Change History\n\n");
        OffsetDateTime updated = s.getUpdatedAt();
        if (updated != null) {
            sb.append("**Last Updated:** ").append(updated).append('\n');
        }
        if (ctx.recentChanges().isEmpty()) {
            appendThinNote(sb, "No recent changes recorded.");
            sb.append('\n');
            return;
        }
        sb.append("\n### Recent Changes\n\n");
        for (ChangeEntry c : ctx.recentChanges()) {
            sb.append("- ");
            if (c.changedAt() != null) sb.append(c.changedAt()).append(" — ");
            if (hasText(c.changedBy())) sb.append(c.changedBy()).append(" — ");
            if (hasText(c.changeType())) sb.append(c.changeType()).append(" — ");
            if (hasText(c.summary())) sb.append(c.summary());
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void renderInternals(StringBuilder sb, ServicePageContext ctx) {
        sb.append("## Internals\n\n");

        sb.append("### Modules\n\n");
        List<ServiceModule> modules = ctx.modules();
        Map<String, String> moduleRefs = ctx.modulePageUrlsByPath();
        if (modules == null || modules.isEmpty()) {
            appendThinNote(sb, "No modules documented yet.");
        } else {
            for (ServiceModule m : modules) {
                String path = hasText(m.modulePath()) ? m.modulePath() : "(root)";
                String ref = moduleRefs == null ? null : moduleRefs.get(m.modulePath());
                sb.append("- ");
                if (hasText(ref)) {
                    sb.append(MarkdownRenderingUtil.wikiLink(ref, path));
                } else {
                    sb.append(path);
                }
                if (hasText(m.artifactId())) {
                    sb.append(" — ").append(coordinatesSummary(m));
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("### Code index\n\n");
        if (hasText(ctx.beansPageUrl())) {
            sb.append(MarkdownRenderingUtil.wikiLink(
                            ctx.beansPageUrl(), "Beans (Spring stereotype classes)"))
                    .append('\n');
        } else {
            appendThinNote(sb, "No code index documented yet.");
        }
        sb.append('\n');

        sb.append("### Tests\n\n");
        if (hasText(ctx.testsPageUrl())) {
            sb.append(MarkdownRenderingUtil.wikiLink(
                            ctx.testsPageUrl(), "Test scenarios"))
                    .append('\n');
        } else {
            appendThinNote(sb, "No tests documented yet.");
        }
        sb.append('\n');

        sb.append("### Configuration\n\n");
        if (hasText(ctx.configurationPageUrl())) {
            sb.append(MarkdownRenderingUtil.wikiLink(
                            ctx.configurationPageUrl(),
                            "Configuration (properties, @Value, @ConfigurationProperties, @Enable*)"))
                    .append('\n');
        } else {
            appendThinNote(sb, "No configuration documented yet.");
        }
        sb.append('\n');

        sb.append("### Endpoints\n\n");
        List<ApiPresentation> endpointsWithRefs = ctx.apis().stream()
                .filter(p -> hasText(p.endpointPageUrl()))
                .toList();
        if (endpointsWithRefs.isEmpty()) {
            appendThinNote(sb, "No endpoints documented yet.");
        } else {
            for (ApiPresentation pres : endpointsWithRefs) {
                String label = pres.api().method() + " " + pres.api().path();
                sb.append("- ").append(MarkdownRenderingUtil.wikiLink(pres.endpointPageUrl(), label))
                        .append('\n');
            }
        }
    }

    private static String coordinatesSummary(ServiceModule m) {
        StringBuilder cs = new StringBuilder();
        if (m.groupId() != null && !m.groupId().isBlank()) {
            cs.append(m.groupId()).append(':');
        }
        cs.append(m.artifactId() == null ? "" : m.artifactId());
        if (m.version() != null && !m.version().isBlank()) {
            cs.append(':').append(m.version());
        }
        return cs.toString();
    }

    // ---- Helpers ---------------------------------------------------------

    private void appendField(StringBuilder sb, String label, String value) {
        if (!hasText(value)) return;
        sb.append("**").append(label).append(":** ").append(value).append('\n');
    }

    private void appendThinNote(StringBuilder sb, String message) {
        sb.append('*').append(message).append("*\n");
    }

    private String joinConsumerNames(List<ApiConsumer> consumers, Map<UUID, String> refs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < consumers.size(); i++) {
            if (i > 0) sb.append(", ");
            ApiConsumer c = consumers.get(i);
            sb.append(renderServiceLink(c.consumerServiceId(), c.consumerServiceName(), refs));
        }
        return sb.toString();
    }

    private String renderServiceLink(UUID serviceId, String name, Map<UUID, String> refs) {
        String ref = (refs == null || serviceId == null) ? null : refs.get(serviceId);
        if (ref == null || ref.isBlank()) {
            return name;
        }
        return ref.equals(name)
                ? MarkdownRenderingUtil.wikiLink(ref)
                : MarkdownRenderingUtil.wikiLink(ref, name);
    }

    private String statusLabel(ServiceStatus status) {
        return status == null ? null : status.name().toLowerCase();
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
