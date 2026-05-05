package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown counterpart of {@link BeansPageRenderer} (Phase 5.8 M2).
 * Stereotype groupings preserve the canonical Spring layering order
 * (RestController → Controller → Service → Repository → Component →
 * Configuration). Public-method signatures are rendered as inline code;
 * javadoc summaries follow on the same line.
 */
@Component
public class BeansMarkdownRenderer {

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
        String pageTitle = pageTitle(s);

        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(pageTitle, "beans", null)));

        sb.append("# ").append(pageTitle).append("\n\n");
        sb.append("Each entry below is a Spring stereotype-annotated class found in this service's ");
        sb.append("source tree. The names + method signatures describe the architectural seams of ");
        sb.append("the service.\n\n");

        if (beans == null || beans.isEmpty()) {
            sb.append("*No bean classes documented yet.*\n");
            renderBackLink(sb, ctx);
            return sb.toString();
        }

        Map<String, List<ServiceBean>> byStereotype = groupByStereotype(beans);
        for (String stereotype : STEREOTYPE_ORDER) {
            List<ServiceBean> group = byStereotype.get(stereotype);
            if (group == null || group.isEmpty()) continue;
            sb.append("## @").append(stereotype).append("\n\n");
            for (ServiceBean bean : group) {
                renderClassBlock(sb, bean);
            }
        }

        renderBackLink(sb, ctx);
        return sb.toString();
    }

    public static String pageTitle(Service service) {
        return service.getName() + " — Beans";
    }

    private void renderClassBlock(StringBuilder sb, ServiceBean bean) {
        String fqn = qualifiedName(bean);
        sb.append("### ").append(fqn).append("\n\n");
        if (hasText(bean.classJavadocSummary())) {
            sb.append(bean.classJavadocSummary()).append("\n\n");
        }
        List<MethodView> methods = parsePublicMethods(bean.publicMethods());
        if (methods.isEmpty()) {
            sb.append("*No public methods declared.*\n\n");
            return;
        }
        for (MethodView m : methods) {
            sb.append("- `").append(m.signature()).append('`');
            if (hasText(m.javadocSummary())) {
                sb.append(" — ").append(m.javadocSummary());
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void renderBackLink(StringBuilder sb, BeansPageContext ctx) {
        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("Back to ")
                    .append(MarkdownRenderingUtil.wikiLink(
                            ctx.serviceConfluenceUrl(), ctx.service().getName()))
                    .append('\n');
        }
    }

    private static Map<String, List<ServiceBean>> groupByStereotype(List<ServiceBean> beans) {
        Map<String, List<ServiceBean>> byStereotype = new LinkedHashMap<>();
        for (ServiceBean b : beans) {
            byStereotype.computeIfAbsent(b.stereotype(), k -> new ArrayList<>()).add(b);
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
            List<MethodView> out = new ArrayList<>(arr.size());
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
