package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import com.atlas.services.TestScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestScenariosMarkdownRendererTest {

    private TestScenariosMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TestScenariosMarkdownRenderer();
    }

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(new TestScenariosPageContext(
                service("orders-svc"), List.of(), null));

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: orders-svc — Tests");
        assertThat(rendered).contains("atlas_page_type: tests");
        assertThat(rendered).contains("---\n\n# orders-svc — Tests");
    }

    @Test
    void whenServiceHasNoScenarios_thenPageRendersThinNote() {
        String rendered = renderer.render(new TestScenariosPageContext(
                service("orders-svc"), List.of(), null));

        assertThat(rendered).containsIgnoringCase("no test scenarios");
    }

    @Test
    void whenServiceHasScenarios_thenScenariosAreGroupedByClassWithMethodNamesAsBullets() {
        Service s = service("orders-svc");
        UUID sid = UUID.randomUUID();
        List<TestScenario> scenarios = List.of(
                new TestScenario(UUID.randomUUID(), sid, "com.example", "OrdersSpec",
                        "whenOrderIsCreated_thenItAppearsInTheList", "tests"),
                new TestScenario(UUID.randomUUID(), sid, "com.example", "OrdersSpec",
                        "whenOrderIsCancelled_thenItIsRemoved", "tests"),
                new TestScenario(UUID.randomUUID(), sid, "com.example", "HealthSpec",
                        "whenServiceIsUp_thenHealthEndpointReturns200", "tests"));

        String rendered = renderer.render(new TestScenariosPageContext(s, scenarios, null));

        assertThat(rendered)
                .contains("## com.example.OrdersSpec")
                .contains("## com.example.HealthSpec")
                .contains("- `whenOrderIsCreated_thenItAppearsInTheList`")
                .contains("- `whenOrderIsCancelled_thenItIsRemoved`")
                .contains("- `whenServiceIsUp_thenHealthEndpointReturns200`");
    }

    @Test
    void whenScenariosAreInDifferentPackages_thenPackageIsIncludedInClassHeading() {
        Service s = service("svc");
        UUID sid = UUID.randomUUID();
        List<TestScenario> scenarios = List.of(
                new TestScenario(UUID.randomUUID(), sid, "com.atlas.intake", "InterviewSpec",
                        "scenario", "tests"),
                new TestScenario(UUID.randomUUID(), sid, "com.atlas.codesync", "CodeSyncSpec",
                        "scenario", "tests"));

        String rendered = renderer.render(new TestScenariosPageContext(s, scenarios, null));

        assertThat(rendered).contains("## com.atlas.intake.InterviewSpec");
        assertThat(rendered).contains("## com.atlas.codesync.CodeSyncSpec");
    }

    @Test
    void whenServicePageRefIsProvided_thenWikiLinkBackLinkIsRendered() {
        Service s = service("svc");

        String rendered = renderer.render(new TestScenariosPageContext(s, List.of(), "svc"));

        assertThat(rendered).contains("Back to [[svc|svc]]");
    }

    @Test
    void whenServicePageRefIsBlank_thenBackLinkIsOmitted() {
        Service s = service("svc");

        String rendered = renderer.render(new TestScenariosPageContext(s, List.of(), null));

        assertThat(rendered).doesNotContain("Back to");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashTests() {
        Service s = service("atlas-intake");

        assertThat(TestScenariosMarkdownRenderer.pageTitle(s))
                .isEqualTo("atlas-intake — Tests");
    }

    @Test
    void whenScenariosHaveSamePackageAndClass_thenMethodsAreOrderedAsGiven() {
        Service s = service("svc");
        UUID sid = UUID.randomUUID();
        List<TestScenario> scenarios = List.of(
                new TestScenario(UUID.randomUUID(), sid, "p", "C", "alpha", "tests"),
                new TestScenario(UUID.randomUUID(), sid, "p", "C", "bravo", "tests"),
                new TestScenario(UUID.randomUUID(), sid, "p", "C", "charlie", "tests"));

        String rendered = renderer.render(new TestScenariosPageContext(s, scenarios, null));

        int alpha = rendered.indexOf("alpha");
        int bravo = rendered.indexOf("bravo");
        int charlie = rendered.indexOf("charlie");
        assertThat(alpha).isLessThan(bravo);
        assertThat(bravo).isLessThan(charlie);
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        Service s = service("svc");
        UUID sid = UUID.randomUUID();
        List<TestScenario> scenarios = List.of(
                new TestScenario(UUID.randomUUID(), sid, "p", "C", "scenario", "tests"));

        String rendered = renderer.render(new TestScenariosPageContext(s, scenarios, "svc"));

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<h3>")
                .doesNotContain("<ul>")
                .doesNotContain("<li>")
                .doesNotContain("<a href=");
    }

    private Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }
}
