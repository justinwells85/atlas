package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Markdown counterpart of {@link ModulePageRenderer} (Phase 5.8 M2).
 *
 * <p>Module path refs in {@link ModulePageContext#moduleConfluencePageUrls()}
 * carry sink-specific values — for the Markdown sink the value is a
 * WikiLink target (typically the module's display path or its module page
 * filename). Same convention as the other Markdown renderers: the field
 * name retains its Confluence-prefixed name from M1 but its content is
 * sink-neutral in M3 onward.
 */
@Component
public class ModuleMarkdownRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT_LABEL = "(root)";

    public String render(ModulePageContext ctx) {
        ServiceModule m = ctx.module();
        Service s = ctx.service();
        String pageTitle = pageTitle(s, m);

        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(pageTitle, "module", null)));

        sb.append("# ").append(displayPath(m.modulePath())).append("\n\n");

        renderParent(sb, ctx);
        renderSubModules(sb, ctx);
        renderCoordinates(sb, m);
        renderVersions(sb, m);
        renderDeclaredDeps(sb, m);

        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("Back to ")
                    .append(MarkdownRenderingUtil.wikiLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append('\n');
        }
        return sb.toString();
    }

    public static String pageTitle(Service service, ServiceModule module) {
        return service.getName() + " — Module: " + displayPath(module.modulePath());
    }

    // ---- Sections -------------------------------------------------------

    private void renderParent(StringBuilder sb, ModulePageContext ctx) {
        sb.append("## Parent module\n\n");
        ServiceModule m = ctx.module();
        if (m.parentPath() == null) {
            sb.append("*Top-level module.*\n\n");
            return;
        }
        ServiceModule parent = findByPath(ctx.allModules(), m.parentPath());
        String parentLabel = displayPath(m.parentPath());
        String parentRef = ctx.moduleConfluencePageUrls() == null ? null
                : ctx.moduleConfluencePageUrls().get(m.parentPath());
        if (parentRef != null && !parentRef.isBlank()) {
            sb.append(MarkdownRenderingUtil.wikiLink(parentRef, parentLabel));
        } else {
            sb.append(parentLabel);
        }
        if (parent != null && hasText(parent.artifactId())) {
            sb.append(" (").append(parent.groupId()).append(':').append(parent.artifactId()).append(')');
        }
        sb.append("\n\n");
    }

    private void renderSubModules(StringBuilder sb, ModulePageContext ctx) {
        sb.append("## Sub-modules\n\n");
        List<ServiceModule> children = childrenOf(ctx);
        if (children.isEmpty()) {
            sb.append("*No sub-modules.*\n\n");
            return;
        }
        for (ServiceModule child : children) {
            String label = displayPath(child.modulePath());
            String ref = ctx.moduleConfluencePageUrls() == null ? null
                    : ctx.moduleConfluencePageUrls().get(child.modulePath());
            sb.append("- ");
            if (ref != null && !ref.isBlank()) {
                sb.append(MarkdownRenderingUtil.wikiLink(ref, label));
            } else {
                sb.append(label);
            }
            if (hasText(child.artifactId())) {
                sb.append(" — ").append(child.artifactId());
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void renderCoordinates(StringBuilder sb, ServiceModule m) {
        sb.append("## Coordinates\n\n");
        appendField(sb, "Group ID", m.groupId());
        appendField(sb, "Artifact ID", m.artifactId());
        appendField(sb, "Version", m.version());
        appendField(sb, "Packaging", m.packaging());
        sb.append('\n');
    }

    private void renderVersions(StringBuilder sb, ServiceModule m) {
        if (!hasText(m.languageVersion()) && !hasText(m.framework())
                && !hasText(m.frameworkVersion())) {
            return;
        }
        sb.append("## Language & Framework\n\n");
        appendField(sb, "Language Version", m.languageVersion());
        appendField(sb, "Framework", m.framework());
        appendField(sb, "Framework Version", m.frameworkVersion());
        sb.append('\n');
    }

    private void renderDeclaredDeps(StringBuilder sb, ServiceModule m) {
        sb.append("## Declared dependencies\n\n");
        List<String> coords = parseDeclaredDeps(m.declaredDeps());
        if (coords.isEmpty()) {
            sb.append("*No declared dependencies.*\n\n");
            return;
        }
        for (String c : coords) {
            sb.append("- ").append(c).append('\n');
        }
        sb.append('\n');
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

    private static void appendField(StringBuilder sb, String label, String value) {
        if (!hasText(value)) return;
        sb.append("**").append(label).append(":** ").append(value).append('\n');
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
