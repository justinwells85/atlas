package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one per-service Beans Confluence page (Phase 5.6 M3 — L5).
 *
 * <p>Sections, in order:
 *
 * <ul>
 *   <li><b>Heading</b> — {@code {service.name} — Beans}</li>
 *   <li><b>Preamble</b> — one paragraph explaining what this page is.</li>
 *   <li><b>Per-stereotype groupings</b> — one section per stereotype that
 *       appears among the beans. Within each section, classes are listed
 *       in fully-qualified-name order (the repository already orders by
 *       package then class).</li>
 *   <li><b>Per-class block</b> — FQN heading; class javadoc summary
 *       (when present); public-method signatures with first-sentence
 *       javadoc (when present).</li>
 *   <li><b>Back-link</b> to the parent service page.</li>
 * </ul>
 *
 * <p>Stereotype grouping order is the canonical Spring layering:
 * controllers first (the user-facing seam), then services / repositories
 * (the service / persistence seams), then components / configuration
 * (the infrastructure seam). Reads top-down as a request-handling story.
 *
 * <p>Pure function: no I/O.
 */
@Component
public class BeansPageRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> STEREOTYPE_ORDER = List.of(
            "RestController",
            "Controller",
            "Service",
            "Repository",
            "Component",
            "Configuration");

    public String render(BeansPageContext ctx) {
        Service s = ctx.service();
        List<ServiceBean> beans = ctx.beans();

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(s.getName())).append(" — Beans</h2>\n");
        sb.append("<p>Each entry below is a Spring stereotype-annotated class found in this service's source tree. ")
                .append("The names + method signatures describe the architectural seams of the service.</p>\n");

        if (beans == null || beans.isEmpty()) {
            sb.append("<p><em>No bean classes documented yet.</em></p>\n");
            renderBackLink(sb, ctx);
            return sb.toString();
        }

        Map<String, List<ServiceBean>> byStereotype = groupByStereotype(beans);
        for (String stereotype : STEREOTYPE_ORDER) {
            List<ServiceBean> group = byStereotype.get(stereotype);
            if (group == null || group.isEmpty()) continue;
            sb.append("<h3>@").append(escape(stereotype)).append("</h3>\n");
            for (ServiceBean bean : group) {
                renderClassBlock(sb, bean);
            }
        }

        renderBackLink(sb, ctx);
        return sb.toString();
    }

    /** Stable Confluence page title: {@code "{service.name} — Beans"}. */
    public static String pageTitle(Service service) {
        return service.getName() + " — Beans";
    }

    // ---- Sections -------------------------------------------------------

    private void renderClassBlock(StringBuilder sb, ServiceBean bean) {
        String fqn = qualifiedName(bean);
        sb.append("<h4>").append(escape(fqn)).append("</h4>\n");
        if (hasText(bean.classJavadocSummary())) {
            sb.append("<p>").append(escape(bean.classJavadocSummary())).append("</p>\n");
        }
        List<MethodView> methods = parsePublicMethods(bean.publicMethods());
        if (methods.isEmpty()) {
            sb.append("<p><em>No public methods declared.</em></p>\n");
            return;
        }
        sb.append("<ul>\n");
        for (MethodView m : methods) {
            sb.append("<li><code>").append(escape(m.signature())).append("</code>");
            if (hasText(m.javadocSummary())) {
                sb.append(" — ").append(escape(m.javadocSummary()));
            }
            sb.append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private void renderBackLink(StringBuilder sb, BeansPageContext ctx) {
        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("<p>Back to ")
                    .append(renderLink(ctx.serviceConfluenceUrl(), ctx.service().getName()))
                    .append("</p>\n");
        }
    }

    // ---- Helpers --------------------------------------------------------

    private static Map<String, List<ServiceBean>> groupByStereotype(List<ServiceBean> beans) {
        Map<String, List<ServiceBean>> byStereotype = new LinkedHashMap<>();
        for (ServiceBean b : beans) {
            byStereotype.computeIfAbsent(b.stereotype(), k -> new java.util.ArrayList<>()).add(b);
        }
        return byStereotype;
    }

    private static String qualifiedName(ServiceBean bean) {
        if (bean.packageName() == null || bean.packageName().isBlank()) {
            return bean.className();
        }
        return bean.packageName() + "." + bean.className();
    }

    private record MethodView(String name, String signature, String javadocSummary) {}

    private static List<MethodView> parsePublicMethods(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            List<MethodView> out = new java.util.ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                out.add(new MethodView(
                        textNode(n, "name"),
                        textNode(n, "signature"),
                        textNode(n, "javadocSummary")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String textNode(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
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
