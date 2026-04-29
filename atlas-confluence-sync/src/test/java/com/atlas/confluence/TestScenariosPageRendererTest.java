package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import com.atlas.services.TestScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "What this service guarantees" page renderer (M3 — code-driven docs).
 * Pure function — given a service and its test scenarios, produces a
 * Confluence storage-format page grouped by package then class. Test
 * names are rendered verbatim because the project rule is they read as
 * specifications (CLAUDE.md §4).
 */
class TestScenariosPageRendererTest {

    private TestScenariosPageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TestScenariosPageRenderer();
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
                .contains("OrdersSpec")
                .contains("HealthSpec")
                .contains("whenOrderIsCreated_thenItAppearsInTheList")
                .contains("whenOrderIsCancelled_thenItIsRemoved")
                .contains("whenServiceIsUp_thenHealthEndpointReturns200");
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

        // Each class heading shows the fully-qualified class name so two classes
        // named the same in different packages are distinguishable.
        assertThat(rendered).contains("com.atlas.intake.InterviewSpec");
        assertThat(rendered).contains("com.atlas.codesync.CodeSyncSpec");
    }

    @Test
    void whenServicePageUrlIsProvided_thenBackLinkIsRendered() {
        Service s = service("svc");
        String svcUrl = "https://atlas.atlassian.net/wiki/spaces/ATLAS/pages/123";

        String rendered = renderer.render(new TestScenariosPageContext(s, List.of(), svcUrl));

        assertThat(rendered).contains(svcUrl);
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashTests() {
        Service s = service("atlas-intake");

        String title = TestScenariosPageRenderer.pageTitle(s);

        assertThat(title).isEqualTo("atlas-intake — Tests");
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

    private Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }
}
