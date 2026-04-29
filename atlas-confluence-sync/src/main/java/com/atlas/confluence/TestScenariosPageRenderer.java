package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.TestScenario;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one per-service "What this service guarantees" Confluence page
 * (M3 — code-driven docs). Test methods are listed verbatim because the
 * project rule is that test names read as specifications (CLAUDE.md §4).
 *
 * Scenarios are grouped by their fully-qualified class name (package +
 * class) — two classes with the same simple name in different packages
 * stay distinguishable. Within a class, methods preserve the input order
 * (caller is expected to pre-sort).
 *
 * Pure function: no I/O.
 */
@Component
public class TestScenariosPageRenderer {

    public String render(TestScenariosPageContext ctx) {
        Service s = ctx.service();
        List<TestScenario> scenarios = ctx.scenarios();

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(s.getName())).append(" — Tests</h2>\n");
        sb.append("<p>Each scenario below is one <code>@Test</code>-annotated method ")
                .append("in the service's source tree. The names read as specifications: ")
                .append("they describe the behaviours this service guarantees.</p>\n");

        if (scenarios == null || scenarios.isEmpty()) {
            sb.append("<p><em>No test scenarios documented yet.</em></p>\n");
        } else {
            // Group by fully-qualified class name in input order so the same
            // class's methods cluster together regardless of input order.
            Map<String, java.util.List<TestScenario>> byClass = new LinkedHashMap<>();
            for (TestScenario sc : scenarios) {
                String fqcn = qualifiedClassName(sc);
                byClass.computeIfAbsent(fqcn, k -> new java.util.ArrayList<>()).add(sc);
            }
            for (Map.Entry<String, java.util.List<TestScenario>> e : byClass.entrySet()) {
                sb.append("<h3>").append(escape(e.getKey())).append("</h3>\n");
                sb.append("<ul>\n");
                for (TestScenario sc : e.getValue()) {
                    sb.append("<li><code>").append(escape(sc.methodName())).append("</code></li>\n");
                }
                sb.append("</ul>\n");
            }
        }

        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("<p>Back to ")
                    .append(renderLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append("</p>\n");
        }
        return sb.toString();
    }

    /**
     * Stable Confluence page title: {@code "{service.name} — Tests"}.
     * Matches the sidebar-tree convention used by per-endpoint pages and
     * the well-known well-pages.
     */
    public static String pageTitle(Service service) {
        return service.getName() + " — Tests";
    }

    private static String qualifiedClassName(TestScenario sc) {
        if (sc.packageName() == null || sc.packageName().isBlank()) {
            return sc.className();
        }
        return sc.packageName() + "." + sc.className();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String renderLink(String href, String text) {
        return "<a href=\"" + escape(href) + "\">" + escape(text) + "</a>";
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
