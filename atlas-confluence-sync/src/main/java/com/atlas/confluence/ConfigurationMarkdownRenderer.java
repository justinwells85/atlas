package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceConfigProperty;
import com.atlas.services.ServiceConfigurationPropertiesType;
import com.atlas.services.ServiceEnableAnnotation;
import com.atlas.services.ServiceValueInjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown counterpart of {@link ConfigurationPageRenderer} (Phase 5.9 M4).
 *
 * <p>Output is GitHub-Flavored Markdown with Obsidian-friendly extensions:
 * YAML front matter; one H3 per property key whose heading text equals the
 * key path, allowing {@code @Value} cross-links to use the
 * {@code [[Service: X — Configuration#atlas.api.key]]} heading-anchor
 * syntax. Profile-specific overrides for the same key live inside that
 * key's section as a small table — one row per (profile, source-file).
 */
@Component
public class ConfigurationMarkdownRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String render(ConfigurationPageContext ctx) {
        Service s = ctx.service();
        String pageTitle = pageTitle(s);

        StringBuilder sb = new StringBuilder();
        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(pageTitle, "configuration", null)));

        sb.append("# ").append(pageTitle).append("\n\n");
        sb.append("This page documents the configuration surface of `")
                .append(s.getName())
                .append("`: declared property keys, `@Value` injection sites, ")
                .append("`@ConfigurationProperties` types, and `@Enable*` activations. ")
                .append("Cross-links join `@Value` sites to the property declarations they read.\n\n");

        List<ServiceConfigProperty> properties = nonNull(ctx.properties());
        List<ServiceValueInjection> injections = nonNull(ctx.valueInjections());
        List<ServiceConfigurationPropertiesType> configTypes = nonNull(ctx.configurationPropertiesTypes());
        List<ServiceEnableAnnotation> enables = nonNull(ctx.enableAnnotations());

        if (properties.isEmpty() && injections.isEmpty()
                && configTypes.isEmpty() && enables.isEmpty()) {
            sb.append("*No configuration data captured yet. ")
                    .append("Run `POST /api/code-sync/refresh-configuration/{serviceId}` to populate.*\n\n");
            renderBackLink(sb, ctx);
            return sb.toString();
        }

        Set<String> declaredKeys = new HashSet<>();
        renderProperties(sb, properties, declaredKeys);
        renderValueInjections(sb, injections, declaredKeys, pageTitle);
        renderConfigurationPropertiesTypes(sb, configTypes);
        renderEnableAnnotations(sb, enables);
        renderBackLink(sb, ctx);
        return sb.toString();
    }

    public static String pageTitle(Service service) {
        return service.getName() + " — Configuration";
    }

    // ---- Sections -------------------------------------------------------

    private void renderProperties(StringBuilder sb, List<ServiceConfigProperty> properties,
                                   Set<String> declaredKeys) {
        sb.append("## Properties\n\n");
        if (properties.isEmpty()) {
            sb.append("*No `application*.{properties,yml,yaml}` files captured.*\n\n");
            return;
        }
        Map<String, List<ServiceConfigProperty>> byKey = groupByKey(properties);
        for (Map.Entry<String, List<ServiceConfigProperty>> e : byKey.entrySet()) {
            String key = e.getKey();
            declaredKeys.add(key);
            // Heading text = key path (no formatting); Obsidian's heading
            // anchor matcher uses the literal heading text, so this lets
            // [[Configuration#atlas.api.key]] resolve from @Value rows.
            sb.append("### ").append(key).append("\n\n");
            sb.append("| Profile | Value | Source file |\n");
            sb.append("|---|---|---|\n");
            for (ServiceConfigProperty p : e.getValue()) {
                sb.append("| ").append(escapeCell(p.profile()))
                        .append(" | `").append(escapeCell(p.value())).append('`')
                        .append(" | `").append(escapeCell(p.sourceFile())).append('`')
                        .append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void renderValueInjections(StringBuilder sb, List<ServiceValueInjection> injections,
                                        Set<String> declaredKeys, String pageTitle) {
        sb.append("## `@Value` injections\n\n");
        if (injections.isEmpty()) {
            sb.append("*No `@Value` injection sites captured.*\n\n");
            return;
        }
        Map<String, List<ServiceValueInjection>> byClass = groupByEnclosingClass(injections);
        for (Map.Entry<String, List<ServiceValueInjection>> e : byClass.entrySet()) {
            sb.append("### ").append(e.getKey()).append("\n\n");
            for (ServiceValueInjection v : e.getValue()) {
                sb.append("- `").append(v.rawSpel()).append("` ")
                        .append("→ `").append(v.memberName()).append("` (")
                        .append(v.memberKind()).append(")");
                if (v.keyPath() != null && !v.keyPath().isEmpty()
                        && declaredKeys.contains(v.keyPath())) {
                    // Obsidian heading-anchor link to the property heading
                    // in this same Configuration page.
                    sb.append(" — ").append(MarkdownRenderingUtil.wikiLink(
                            pageTitle + "#" + v.keyPath(), "declared in properties"));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
    }

    private void renderConfigurationPropertiesTypes(StringBuilder sb,
                                                      List<ServiceConfigurationPropertiesType> types) {
        sb.append("## `@ConfigurationProperties` types\n\n");
        if (types.isEmpty()) {
            sb.append("*No `@ConfigurationProperties` types captured.*\n\n");
            return;
        }
        for (ServiceConfigurationPropertiesType t : types) {
            String prefixDisplay = (t.prefix() == null || t.prefix().isEmpty())
                    ? "(no prefix)"
                    : t.prefix();
            sb.append("### ").append(t.enclosingClass()).append("\n\n");
            sb.append("**Prefix:** `").append(prefixDisplay).append("`")
                    .append(" &middot; **Kind:** ").append(t.typeKind()).append("\n\n");
            List<ComponentView> components = parseComponents(t.components());
            if (components.isEmpty()) {
                sb.append("*No declared components.*\n\n");
                continue;
            }
            sb.append("| Component | Declared type |\n");
            sb.append("|---|---|\n");
            for (ComponentView c : components) {
                sb.append("| `").append(escapeCell(c.name())).append('`')
                        .append(" | `").append(escapeCell(c.declaredType())).append('`')
                        .append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void renderEnableAnnotations(StringBuilder sb, List<ServiceEnableAnnotation> enables) {
        sb.append("## `@Enable*` activations\n\n");
        if (enables.isEmpty()) {
            sb.append("*No `@Enable*` annotations captured.*\n\n");
            return;
        }
        Map<String, List<ServiceEnableAnnotation>> byClass = groupByEnclosingClass2(enables);
        for (Map.Entry<String, List<ServiceEnableAnnotation>> e : byClass.entrySet()) {
            sb.append("### ").append(e.getKey()).append("\n\n");
            for (ServiceEnableAnnotation a : e.getValue()) {
                sb.append("- `@").append(a.annotationSimpleName()).append("` ")
                        .append("(`").append(a.annotationFqn()).append("`)");
                if (a.javadocFirstSentence() != null && !a.javadocFirstSentence().isBlank()) {
                    sb.append(" — ").append(a.javadocFirstSentence());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
    }

    private void renderBackLink(StringBuilder sb, ConfigurationPageContext ctx) {
        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("Back to ")
                    .append(MarkdownRenderingUtil.wikiLink(
                            ctx.serviceConfluenceUrl(), ctx.service().getName()))
                    .append('\n');
        }
    }

    // ---- Helpers --------------------------------------------------------

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, List<ServiceConfigProperty>> groupByKey(
            List<ServiceConfigProperty> properties) {
        // Order: insertion order, with default-profile rows surfacing first
        // for each key (matches the Confluence renderer's profile ordering).
        Map<String, List<ServiceConfigProperty>> out = new LinkedHashMap<>();
        for (ServiceConfigProperty p : properties) {
            if ("default".equals(p.profile())) {
                out.computeIfAbsent(p.keyPath(), k -> new ArrayList<>()).add(p);
            }
        }
        for (ServiceConfigProperty p : properties) {
            if (!"default".equals(p.profile())) {
                out.computeIfAbsent(p.keyPath(), k -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    private static Map<String, List<ServiceValueInjection>> groupByEnclosingClass(
            List<ServiceValueInjection> injections) {
        Map<String, List<ServiceValueInjection>> byClass = new LinkedHashMap<>();
        for (ServiceValueInjection v : injections) {
            byClass.computeIfAbsent(v.enclosingClass(), k -> new ArrayList<>()).add(v);
        }
        return byClass;
    }

    private static Map<String, List<ServiceEnableAnnotation>> groupByEnclosingClass2(
            List<ServiceEnableAnnotation> enables) {
        Map<String, List<ServiceEnableAnnotation>> byClass = new LinkedHashMap<>();
        for (ServiceEnableAnnotation a : enables) {
            byClass.computeIfAbsent(a.enclosingClass(), k -> new ArrayList<>()).add(a);
        }
        return byClass;
    }

    private record ComponentView(String name, String declaredType) {}

    private static List<ComponentView> parseComponents(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            List<ComponentView> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                out.add(new ComponentView(
                        textNode(n, "name"),
                        textNode(n, "declaredType")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String textNode(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    /**
     * Escape characters that would break a GFM table cell ({@code |} and
     * newlines). Backslashes inside cells render literally.
     */
    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", "");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
