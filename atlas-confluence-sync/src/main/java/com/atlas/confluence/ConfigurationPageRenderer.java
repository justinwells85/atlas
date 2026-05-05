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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders one per-service Configuration Confluence page (Phase 5.9 M4).
 *
 * <p>Sections, in order:
 * <ul>
 *   <li><b>Heading</b> — {@code "{service.name} — Configuration"}</li>
 *   <li><b>Preamble</b> — one paragraph explaining what this page is.</li>
 *   <li><b>Properties</b> — table per profile, with anchor {@code id="key-X"}
 *       on the first row matching each key (used as the cross-link target
 *       from {@code @Value} rows).</li>
 *   <li><b>{@code @Value} injections</b> — grouped by enclosing class. Each
 *       row shows the SpEL plus a "→ declared in properties" cross-link
 *       when the resolved key matches a captured property row.</li>
 *   <li><b>{@code @ConfigurationProperties} types</b> — one block per type
 *       with prefix, kind, and the declared component list.</li>
 *   <li><b>{@code @Enable*} activations</b> — one row per annotation use-site
 *       with FQN and (when same-module javadoc resolved) first-sentence
 *       javadoc.</li>
 *   <li><b>Back-link</b> to the parent service page.</li>
 * </ul>
 *
 * <p>Pure function: no I/O.
 */
@Component
public class ConfigurationPageRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String render(ConfigurationPageContext ctx) {
        Service s = ctx.service();
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(s.getName())).append(" — Configuration</h2>\n");
        sb.append("<p>This page documents the configuration surface of <code>")
                .append(escape(s.getName()))
                .append("</code>: declared property keys, ")
                .append("<code>@Value</code> injection sites, ")
                .append("<code>@ConfigurationProperties</code> types, and ")
                .append("<code>@Enable*</code> activations. Cross-links join ")
                .append("<code>@Value</code> sites to the property declarations they read.</p>\n");

        List<ServiceConfigProperty> properties = nonNull(ctx.properties());
        List<ServiceValueInjection> injections = nonNull(ctx.valueInjections());
        List<ServiceConfigurationPropertiesType> configTypes = nonNull(ctx.configurationPropertiesTypes());
        List<ServiceEnableAnnotation> enables = nonNull(ctx.enableAnnotations());

        if (properties.isEmpty() && injections.isEmpty()
                && configTypes.isEmpty() && enables.isEmpty()) {
            sb.append("<p><em>No configuration data captured yet. ")
                    .append("Run <code>POST /api/code-sync/refresh-configuration/{serviceId}</code> to populate.</em></p>\n");
            renderBackLink(sb, ctx);
            return sb.toString();
        }

        // Index properties by keyPath for the cross-link from @Value rows.
        // First row per key wins as the anchor target; subsequent rows for
        // the same key (different profiles) re-use the same anchor.
        Set<String> anchoredKeys = new HashSet<>();

        renderProperties(sb, properties, anchoredKeys);
        renderValueInjections(sb, injections, anchoredKeys);
        renderConfigurationPropertiesTypes(sb, configTypes);
        renderEnableAnnotations(sb, enables);
        renderBackLink(sb, ctx);
        return sb.toString();
    }

    /** Stable Confluence page title: {@code "{service.name} — Configuration"}. */
    public static String pageTitle(Service service) {
        return service.getName() + " — Configuration";
    }

    // ---- Sections -------------------------------------------------------

    private void renderProperties(StringBuilder sb,
                                   List<ServiceConfigProperty> properties,
                                   Set<String> anchoredKeys) {
        sb.append("<h3>Properties</h3>\n");
        if (properties.isEmpty()) {
            sb.append("<p><em>No <code>application*.{properties,yml,yaml}</code> files captured.</em></p>\n");
            return;
        }
        Map<String, List<ServiceConfigProperty>> byProfile = groupByProfile(properties);
        for (Map.Entry<String, List<ServiceConfigProperty>> e : byProfile.entrySet()) {
            sb.append("<h4>Profile: ").append(escape(e.getKey())).append("</h4>\n");
            sb.append("<table><tbody>\n");
            sb.append("<tr><th>Key</th><th>Value</th><th>Source file</th></tr>\n");
            for (ServiceConfigProperty p : e.getValue()) {
                sb.append("<tr>");
                if (anchoredKeys.add(p.keyPath())) {
                    sb.append("<td id=\"").append(escape(anchorIdForKey(p.keyPath())))
                            .append("\"><code>").append(escape(p.keyPath())).append("</code></td>");
                } else {
                    sb.append("<td><code>").append(escape(p.keyPath())).append("</code></td>");
                }
                sb.append("<td><code>").append(escape(p.value())).append("</code></td>");
                sb.append("<td><code>").append(escape(p.sourceFile())).append("</code></td>");
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");
        }
    }

    private void renderValueInjections(StringBuilder sb,
                                        List<ServiceValueInjection> injections,
                                        Set<String> declaredKeys) {
        sb.append("<h3><code>@Value</code> injections</h3>\n");
        if (injections.isEmpty()) {
            sb.append("<p><em>No <code>@Value</code> injection sites captured.</em></p>\n");
            return;
        }
        Map<String, List<ServiceValueInjection>> byClass = groupByEnclosingClass(injections);
        for (Map.Entry<String, List<ServiceValueInjection>> e : byClass.entrySet()) {
            sb.append("<h4>").append(escape(e.getKey())).append("</h4>\n");
            sb.append("<ul>\n");
            for (ServiceValueInjection v : e.getValue()) {
                sb.append("<li><code>").append(escape(v.rawSpel())).append("</code> ")
                        .append("→ <code>").append(escape(v.memberName())).append("</code> ")
                        .append("(").append(escape(v.memberKind())).append(")");
                if (v.keyPath() != null && !v.keyPath().isEmpty()
                        && declaredKeys.contains(v.keyPath())) {
                    sb.append(" — <a href=\"#")
                            .append(escape(anchorIdForKey(v.keyPath())))
                            .append("\">declared in properties</a>");
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }
    }

    private void renderConfigurationPropertiesTypes(StringBuilder sb,
                                                      List<ServiceConfigurationPropertiesType> types) {
        sb.append("<h3><code>@ConfigurationProperties</code> types</h3>\n");
        if (types.isEmpty()) {
            sb.append("<p><em>No <code>@ConfigurationProperties</code> types captured.</em></p>\n");
            return;
        }
        for (ServiceConfigurationPropertiesType t : types) {
            String prefixDisplay = (t.prefix() == null || t.prefix().isEmpty())
                    ? "(no prefix)"
                    : t.prefix();
            sb.append("<h4>").append(escape(t.enclosingClass())).append("</h4>\n");
            sb.append("<p><strong>Prefix:</strong> <code>").append(escape(prefixDisplay)).append("</code> ")
                    .append("&nbsp;&middot;&nbsp; <strong>Kind:</strong> ").append(escape(t.typeKind())).append("</p>\n");
            List<ComponentView> components = parseComponents(t.components());
            if (components.isEmpty()) {
                sb.append("<p><em>No declared components.</em></p>\n");
                continue;
            }
            sb.append("<table><tbody>\n");
            sb.append("<tr><th>Component</th><th>Declared type</th></tr>\n");
            for (ComponentView c : components) {
                sb.append("<tr><td><code>").append(escape(c.name())).append("</code></td>")
                        .append("<td><code>").append(escape(c.declaredType())).append("</code></td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }
    }

    private void renderEnableAnnotations(StringBuilder sb,
                                          List<ServiceEnableAnnotation> enables) {
        sb.append("<h3><code>@Enable*</code> activations</h3>\n");
        if (enables.isEmpty()) {
            sb.append("<p><em>No <code>@Enable*</code> annotations captured.</em></p>\n");
            return;
        }
        Map<String, List<ServiceEnableAnnotation>> byClass = groupByEnclosingClass2(enables);
        for (Map.Entry<String, List<ServiceEnableAnnotation>> e : byClass.entrySet()) {
            sb.append("<h4>").append(escape(e.getKey())).append("</h4>\n");
            sb.append("<ul>\n");
            for (ServiceEnableAnnotation a : e.getValue()) {
                sb.append("<li><code>@").append(escape(a.annotationSimpleName())).append("</code> ")
                        .append("(<code>").append(escape(a.annotationFqn())).append("</code>)");
                if (a.javadocFirstSentence() != null && !a.javadocFirstSentence().isBlank()) {
                    sb.append(" — ").append(escape(a.javadocFirstSentence()));
                }
                sb.append("</li>\n");
            }
            sb.append("</ul>\n");
        }
    }

    private void renderBackLink(StringBuilder sb, ConfigurationPageContext ctx) {
        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("<p>Back to ")
                    .append(renderLink(ctx.serviceConfluenceUrl(), ctx.service().getName()))
                    .append("</p>\n");
        }
    }

    // ---- Helpers --------------------------------------------------------

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, List<ServiceConfigProperty>> groupByProfile(
            List<ServiceConfigProperty> properties) {
        Map<String, List<ServiceConfigProperty>> byProfile = new LinkedHashMap<>();
        // Default profile first so the "common case" reads first.
        for (ServiceConfigProperty p : properties) {
            if ("default".equals(p.profile())) {
                byProfile.computeIfAbsent("default", k -> new ArrayList<>()).add(p);
            }
        }
        for (ServiceConfigProperty p : properties) {
            if (!"default".equals(p.profile())) {
                byProfile.computeIfAbsent(p.profile(), k -> new ArrayList<>()).add(p);
            }
        }
        return byProfile;
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

    /**
     * Confluence anchor id for a property key cross-link target. Replaces
     * dots / colons / brackets with hyphens so the id contains only
     * a-z / 0-9 / hyphen — the safest subset across rendering paths.
     */
    private static String anchorIdForKey(String key) {
        return "key-" + key.replaceAll("[^A-Za-z0-9-]", "-");
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
