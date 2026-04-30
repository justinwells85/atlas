package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 Beans Confluence-page renderer (Phase 5.6 M3). Pure function.
 * Per-stereotype groupings; per-class blocks with FQN, optional class
 * javadoc summary, and public-method signatures with first-sentence
 * javadoc.
 */
class BeansPageRendererTest {

    private BeansPageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new BeansPageRenderer();
    }

    @Test
    void whenServiceHasBeansAcrossStereotypes_thenOneSectionPerStereotype() {
        ServiceBean controller = bean("com.example.web", "OrderController", "RestController",
                "Handles order endpoints.",
                "[{\"name\":\"create\",\"signature\":\"Order create(CreateRequest req)\",\"javadocSummary\":\"Create an order.\"}]");
        ServiceBean svc = bean("com.example.svc", "OrderService", "Service",
                null,
                "[{\"name\":\"persist\",\"signature\":\"void persist(Order o)\",\"javadocSummary\":null}]");

        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(controller, svc),
                "https://wiki/orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("@RestController")
                .contains("@Service")
                .contains("com.example.web.OrderController")
                .contains("com.example.svc.OrderService");
        // Stereotype ordering: RestController appears before Service.
        assertThat(rendered.indexOf("@RestController"))
                .isLessThan(rendered.indexOf("@Service"));
    }

    @Test
    void whenClassHasJavadocAndPublicMethods_thenSummaryAndSignaturesRendered() {
        ServiceBean svc = bean("com.example", "OrderService", "Service",
                "Coordinates checkout flow.",
                "[{\"name\":\"create\",\"signature\":\"Order create(Request r)\",\"javadocSummary\":\"Create an order.\"}]");

        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(svc),
                "https://wiki/orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Coordinates checkout flow.")
                .contains("Order create(Request r)")
                .contains("Create an order.");
    }

    @Test
    void whenClassHasNoPublicMethods_thenSectionRendersThinNote() {
        ServiceBean svc = bean("com.example", "EmptyService", "Service",
                null,
                "[]");

        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(svc),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).containsIgnoringCase("No public methods declared");
    }

    @Test
    void whenServiceHasNoBeans_thenPageRendersThinNoteRatherThanFailing() {
        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(),
                "https://wiki/orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).containsIgnoringCase("No bean classes documented");
        // Heading is still present so the page is structurally consistent.
        assertThat(rendered).contains("orders-svc — Beans");
    }

    @Test
    void whenServiceHasConfluenceUrl_thenBackLinkIsRendered() {
        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(),
                "https://wiki/orders-svc-page");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Back to")
                .contains("https://wiki/orders-svc-page");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashBeans() {
        Service s = service("orders-svc");
        assertThat(BeansPageRenderer.pageTitle(s)).isEqualTo("orders-svc — Beans");
    }

    @Test
    void whenStereotypeNotInCanonicalOrder_thenItIsStillRenderedOrDoesNotBreakRender() {
        // Defensive: if some future writer sneaks a stereotype outside the
        // canonical set into the live view, the renderer should not crash.
        ServiceBean odd = bean("com.example", "Mystery", "FutureStereotype",
                null, "[]");

        BeansPageContext ctx = new BeansPageContext(
                service("svc"),
                List.of(odd),
                null);

        // Renderer skips unknown stereotypes silently — defensive, not surfaced.
        // The class doesn't appear, but the page still renders the heading + preamble.
        String rendered = renderer.render(ctx);
        assertThat(rendered).contains("svc — Beans");
        assertThat(rendered).doesNotContain("Mystery");
    }

    // ---- helpers --------------------------------------------------------

    private Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }

    private ServiceBean bean(String packageName, String className, String stereotype,
                             String classJavadoc, String publicMethodsJson) {
        return new ServiceBean(
                UUID.randomUUID(), UUID.randomUUID(),
                "", packageName, className, stereotype,
                classJavadoc, publicMethodsJson,
                "source-tree", null);
    }
}
