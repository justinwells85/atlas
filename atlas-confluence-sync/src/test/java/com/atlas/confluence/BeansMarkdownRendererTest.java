package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BeansMarkdownRendererTest {

    private BeansMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new BeansMarkdownRenderer();
    }

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        BeansPageContext ctx = new BeansPageContext(service("orders-svc"), List.of(), null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: orders-svc — Beans");
        assertThat(rendered).contains("atlas_page_type: beans");
        assertThat(rendered).contains("---\n\n# orders-svc — Beans");
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
                "orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("## @RestController")
                .contains("## @Service")
                .contains("### com.example.web.OrderController")
                .contains("### com.example.svc.OrderService");
        assertThat(rendered.indexOf("## @RestController"))
                .isLessThan(rendered.indexOf("## @Service"));
    }

    @Test
    void whenClassHasJavadocAndPublicMethods_thenSummaryAndSignaturesRendered() {
        ServiceBean svc = bean("com.example", "OrderService", "Service",
                "Coordinates checkout flow.",
                "[{\"name\":\"create\",\"signature\":\"Order create(Request r)\",\"javadocSummary\":\"Create an order.\"}]");

        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(svc),
                "orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Coordinates checkout flow.")
                .contains("- `Order create(Request r)` — Create an order.");
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
                "orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).containsIgnoringCase("No bean classes documented");
        assertThat(rendered).contains("orders-svc — Beans");
    }

    @Test
    void whenServicePageRefIsProvided_thenWikiLinkBackLinkIsRendered() {
        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(),
                "orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("Back to [[orders-svc|orders-svc]]");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashBeans() {
        Service s = service("orders-svc");
        assertThat(BeansMarkdownRenderer.pageTitle(s)).isEqualTo("orders-svc — Beans");
    }

    @Test
    void whenStereotypeNotInCanonicalOrder_thenItIsSilentlySkipped() {
        ServiceBean odd = bean("com.example", "Mystery", "FutureStereotype",
                null, "[]");

        BeansPageContext ctx = new BeansPageContext(
                service("svc"),
                List.of(odd),
                null);

        String rendered = renderer.render(ctx);
        assertThat(rendered).contains("svc — Beans");
        assertThat(rendered).doesNotContain("Mystery");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        ServiceBean controller = bean("com.example.web", "OrderController", "RestController",
                "Handles orders.",
                "[{\"name\":\"create\",\"signature\":\"Order create(Req r)\",\"javadocSummary\":\"Create.\"}]");

        BeansPageContext ctx = new BeansPageContext(
                service("orders-svc"),
                List.of(controller),
                "orders-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<h3>")
                .doesNotContain("<h4>")
                .doesNotContain("<ul>")
                .doesNotContain("<a href=");
    }

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
