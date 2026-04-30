package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5.6 M3 — pure AST extraction of Spring stereotype classes from
 * one Java source. The narrow stereotype set (Option A from the plan):
 * {@code @RestController}, {@code @Controller}, {@code @Service},
 * {@code @Repository}, {@code @Component}, {@code @Configuration}.
 */
class JavaBeanExtractorTest {

    private JavaBeanExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JavaBeanExtractor();
    }

    @Test
    void whenSourceHasRestController_thenBeanRecordCarriesStereotype() {
        String src = """
                package com.example.web;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class OrderController {
                    public String hello() { return "hi"; }
                }
                """;

        List<BeanRecord> beans = extractor.extract(src);

        assertThat(beans).hasSize(1);
        assertThat(beans.get(0).packageName()).isEqualTo("com.example.web");
        assertThat(beans.get(0).className()).isEqualTo("OrderController");
        assertThat(beans.get(0).stereotype()).isEqualTo("RestController");
    }

    @Test
    void whenSourceHasServiceWithPublicMethod_thenSignatureCarriesParamsAndReturnType() {
        String src = """
                package com.example.svc;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public Order createOrder(CreateRequest req, String idempotencyKey) {
                        return null;
                    }
                }
                """;

        BeanRecord bean = extractor.extract(src).get(0);

        assertThat(bean.publicMethods()).hasSize(1);
        BeanRecord.MethodRecord m = bean.publicMethods().get(0);
        assertThat(m.name()).isEqualTo("createOrder");
        assertThat(m.signature())
                .contains("Order createOrder")
                .contains("CreateRequest req")
                .contains("String idempotencyKey");
    }

    @Test
    void whenClassHasJavadoc_thenFirstSentenceIsCaptured() {
        String src = """
                package com.example;
                import org.springframework.stereotype.Service;
                /**
                 * Coordinates checkout flow. Wraps the payment provider plus
                 * inventory reservation.
                 */
                @Service
                public class CheckoutService {
                }
                """;

        BeanRecord bean = extractor.extract(src).get(0);
        assertThat(bean.classJavadocSummary())
                .isEqualTo("Coordinates checkout flow.");
    }

    @Test
    void whenMethodHasJavadoc_thenFirstSentenceIsCaptured() {
        String src = """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    /**
                     * Create an order. Validates and persists.
                     */
                    public Order create() { return null; }
                }
                """;

        BeanRecord bean = extractor.extract(src).get(0);
        assertThat(bean.publicMethods().get(0).javadocSummary())
                .isEqualTo("Create an order.");
    }

    @Test
    void whenClassHasNoStereotype_thenExtractorSkipsIt() {
        String src = """
                package com.example;
                public class OrderDto {
                    public String name;
                }
                """;

        assertThat(extractor.extract(src)).isEmpty();
    }

    @Test
    void whenClassIsAnInterface_thenExtractorSkipsIt() {
        // Spring Data repository interfaces — common anti-stereotype.
        String src = """
                package com.example;
                import org.springframework.stereotype.Repository;
                @Repository
                public interface OrderRepository {
                    void save(Object o);
                }
                """;

        assertThat(extractor.extract(src)).isEmpty();
    }

    @Test
    void whenClassIsAnEnum_thenExtractorSkipsIt() {
        // Plain enum with no stereotype — ignored by extractor.
        String src = """
                package com.example;
                public enum OrderStatus {
                    PENDING, COMPLETED
                }
                """;

        assertThat(extractor.extract(src)).isEmpty();
    }

    @Test
    void whenClassHasOnlyPrivateMethods_thenPublicMethodsListIsEmpty() {
        String src = """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class HiddenService {
                    private void doStuff() {}
                }
                """;

        BeanRecord bean = extractor.extract(src).get(0);
        assertThat(bean.publicMethods()).isEmpty();
    }

    @Test
    void whenClassIsNested_thenExtractorSkipsTheNestedClass() {
        // Top-level non-stereotype, nested-stereotype: extractor should
        // surface neither — top-level lacks the annotation, nested isn't
        // top-level. Documents the "top-level only" rule.
        String src = """
                package com.example;
                import org.springframework.stereotype.Service;
                public class Outer {
                    @Service
                    public static class InnerService {}
                }
                """;

        assertThat(extractor.extract(src)).isEmpty();
    }

    @Test
    void whenSourceUsesJava21FeaturesLikeRecordsAndSwitchExpressions_thenItStillParses() {
        // Regression: real Atlas services (Java 21 LTS) use records inline
        // and switch expressions in stereotype classes. JavaParser's default
        // config rejects everything past Java 8, so without an explicit
        // language-level pin, ~12 source files per atlas-intake refresh
        // were silently skipped during the Phase 5.6 M4 dogfood.
        String src = """
                package com.example.web;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class OrderController {
                    public record OrderRequest(String sku, int qty) {}
                    public String classify(int code) {
                        return switch (code) {
                            case 1 -> "alpha";
                            case 2, 3 -> "beta";
                            default -> "gamma";
                        };
                    }
                }
                """;

        List<BeanRecord> beans = extractor.extract(src);

        assertThat(beans).hasSize(1);
        assertThat(beans.get(0).className()).isEqualTo("OrderController");
        assertThat(beans.get(0).stereotype()).isEqualTo("RestController");
        assertThat(beans.get(0).publicMethods()).extracting(BeanRecord.MethodRecord::name)
                .contains("classify");
    }

    @Test
    void whenSourceHasMultipleStereotypeAnnotations_thenFirstOneWins() {
        // A class annotated with both @RestController and @Service surfaces
        // as RestController (the more specific marker, declared first).
        String src = """
                package com.example;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.stereotype.Service;
                @RestController
                @Service
                public class HybridController {
                }
                """;

        BeanRecord bean = extractor.extract(src).get(0);
        assertThat(bean.stereotype()).isEqualTo("RestController");
    }
}
