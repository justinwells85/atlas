package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders one L4 Maven-module Confluence page (Phase 5.6 M2).
 *
 * <p>Sections, in order:
 *
 * <ul>
 *   <li><b>Heading</b> — {@code {service.name} — Module: {module-path}}</li>
 *   <li><b>Parent module</b> — link to the parent module's page, or
 *       "Top-level module" when this is the service-root.</li>
 *   <li><b>Sub-modules</b> — link list of children, or "No sub-modules."</li>
 *   <li><b>Coordinates</b> — groupId, artifactId, version, packaging.</li>
 *   <li><b>Language / Framework versions</b> — module-specific values when
 *       declared, otherwise "Inherited from parent."</li>
 *   <li><b>Declared dependencies</b> — list of {@code groupId:artifactId}
 *       coords from this pom (org-prefix-filtered upstream by code-sync).</li>
 *   <li><b>Back-link</b> to the parent service page.</li>
 * </ul>
 *
 * <p>Pure function: no I/O. Discovers parent / children by walking
 * {@link ModulePageContext#allModules()} against the current module's
 * {@code parent_path}.
 */
@Component
public class ModulePageRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT_LABEL = "(root)";

    public String render(ModulePageContext ctx) {
        ServiceModule m = ctx.module();
        Service s = ctx.service();

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(displayPath(m.modulePath()))).append("</h2>\n");

        renderParent(sb, ctx);
        renderSubModules(sb, ctx);
        renderCoordinates(sb, m);
        renderVersions(sb, m);
        renderDeclaredDeps(sb, m);

        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("<p>Back to ")
                    .append(renderLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append("</p>\n");
        }
        return sb.toString();
    }

    /**
     * Stable Confluence page title for a module page. The em-dash matches
     * the convention from per-endpoint pages and Tests pages so children
     * sort naturally in the Confluence sidebar tree.
     */
    public static String pageTitle(Service service, ServiceModule module) {
        return service.getName() + " — Module: " + displayPath(module.modulePath());
    }

    // ---- Sections -------------------------------------------------------

    private void renderParent(StringBuilder sb, ModulePageContext ctx) {
        sb.append("<h3>Parent module</h3>\n");
        ServiceModule m = ctx.module();
        if (m.parentPath() == null) {
            sb.append("<p><em>Top-level module.</em></p>\n");
            return;
        }
        ServiceModule parent = findByPath(ctx.allModules(), m.parentPath());
        String parentLabel = displayPath(m.parentPath());
        String parentUrl = ctx.moduleConfluencePageUrls() == null ? null
                : ctx.moduleConfluencePageUrls().get(m.parentPath());
        sb.append("<p>");
        if (parentUrl != null && !parentUrl.isBlank()) {
            sb.append(renderLink(parentUrl, parentLabel));
        } else {
            sb.append(escape(parentLabel));
        }
        if (parent != null && hasText(parent.artifactId())) {
            sb.append(" (").append(escape(parent.groupId() + ":" + parent.artifactId())).append(")");
        }
        sb.append("</p>\n");
    }

    private void renderSubModules(StringBuilder sb, ModulePageContext ctx) {
        sb.append("<h3>Sub-modules</h3>\n");
        List<ServiceModule> children = childrenOf(ctx);
        if (children.isEmpty()) {
            sb.append("<p><em>No sub-modules.</em></p>\n");
            return;
        }
        sb.append("<ul>\n");
        for (ServiceModule child : children) {
            String label = displayPath(child.modulePath());
            String url = ctx.moduleConfluencePageUrls() == null ? null
                    : ctx.moduleConfluencePageUrls().get(child.modulePath());
            sb.append("<li>");
            if (url != null && !url.isBlank()) {
                sb.append(renderLink(url, label));
            } else {
                sb.append(escape(label));
            }
            if (hasText(child.artifactId())) {
                sb.append(" — ").append(escape(child.artifactId()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private void renderCoordinates(StringBuilder sb, ServiceModule m) {
        sb.append("<h3>Coordinates</h3>\n");
        appendField(sb, "Group ID", m.groupId());
        appendField(sb, "Artifact ID", m.artifactId());
        appendField(sb, "Version", m.version());
        appendField(sb, "Packaging", m.packaging());
    }

    private void renderVersions(StringBuilder sb, ServiceModule m) {
        if (!hasText(m.languageVersion()) && !hasText(m.framework())
                && !hasText(m.frameworkVersion())) {
            return;
        }
        sb.append("<h3>Language &amp; Framework</h3>\n");
        appendField(sb, "Language Version", m.languageVersion());
        appendField(sb, "Framework", m.framework());
        appendField(sb, "Framework Version", m.frameworkVersion());
    }

    private void renderDeclaredDeps(StringBuilder sb, ServiceModule m) {
        sb.append("<h3>Declared dependencies</h3>\n");
        List<String> coords = parseDeclaredDeps(m.declaredDeps());
        if (coords.isEmpty()) {
            sb.append("<p><em>No declared dependencies.</em></p>\n");
            return;
        }
        sb.append("<ul>\n");
        for (String c : coords) {
            sb.append("<li>").append(escape(c)).append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    // ---- Helpers --------------------------------------------------------

    private static List<String> parseDeclaredDeps(String declaredDepsJson) {
        if (declaredDepsJson == null || declaredDepsJson.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(declaredDepsJson);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                if (n.isTextual()) out.add(n.asText());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ServiceModule findByPath(List<ServiceModule> all, String path) {
        if (all == null || path == null) return null;
        for (ServiceModule sm : all) {
            if (Objects.equals(sm.modulePath(), path)) return sm;
        }
        return null;
    }

    private static List<ServiceModule> childrenOf(ModulePageContext ctx) {
        String path = ctx.module().modulePath();
        List<ServiceModule> out = new ArrayList<>();
        for (ServiceModule sm : ctx.allModules()) {
            if (Objects.equals(sm.parentPath(), path)) out.add(sm);
        }
        out.sort((a, b) -> a.modulePath().compareTo(b.modulePath()));
        return out;
    }

    private static String displayPath(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return ROOT_LABEL;
        return modulePath;
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (!hasText(value)) return;
        sb.append("<p><strong>").append(label).append(":</strong> ")
                .append(escape(value)).append("</p>\n");
    }

    private String renderLink(String href, String text) {
        return "<a href=\"" + escape(href) + "\">" + escape(text) + "</a>";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
