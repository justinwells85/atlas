package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.TestScenario;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown counterpart of {@link TestScenariosPageRenderer} (Phase 5.8 M2).
 * Test methods are listed verbatim because their names read as specifications
 * (CLAUDE.md §4). Scenarios group by FQ class name in input order.
 *
 * <p>The {@link TestScenariosPageContext#serviceConfluenceUrl()} field is
 * read as a sink-neutral back-link target — for the Markdown sink, the
 * coordinator passes a WikiLink target (typically the parent service
 * name). M3 generalizes the field's semantics; for now the rendering is
 * uniform: non-blank → wrap as WikiLink; blank/null → omit the back-link.
 */
@Component
public class TestScenariosMarkdownRenderer {

    public String render(TestScenariosPageContext ctx) {
        Service s = ctx.service();
        List<TestScenario> scenarios = ctx.scenarios();
        String pageTitle = pageTitle(s);

        StringBuilder sb = new StringBuilder();

        sb.append(MarkdownRenderingUtil.frontMatter(
                MarkdownRenderingUtil.baseFrontMatter(pageTitle, "tests", null)));

        sb.append("# ").append(pageTitle).append("\n\n");
        sb.append("Each scenario below is one `@Test`-annotated method in the service's source tree. ");
        sb.append("The names read as specifications: they describe the behaviours this service ");
        sb.append("guarantees.\n\n");

        if (scenarios == null || scenarios.isEmpty()) {
            sb.append("*No test scenarios documented yet.*\n");
        } else {
            Map<String, List<TestScenario>> byClass = new LinkedHashMap<>();
            for (TestScenario sc : scenarios) {
                byClass.computeIfAbsent(qualifiedClassName(sc), k -> new ArrayList<>()).add(sc);
            }
            for (Map.Entry<String, List<TestScenario>> e : byClass.entrySet()) {
                sb.append("## ").append(e.getKey()).append("\n\n");
                for (TestScenario sc : e.getValue()) {
                    sb.append("- `").append(sc.methodName()).append("`\n");
                }
                sb.append('\n');
            }
        }

        if (hasText(ctx.serviceConfluenceUrl())) {
            sb.append("Back to ")
                    .append(MarkdownRenderingUtil.wikiLink(ctx.serviceConfluenceUrl(), s.getName()))
                    .append('\n');
        }

        return sb.toString();
    }

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
}
