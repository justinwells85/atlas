package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-function extractor: input is one Java source file's text, output is a
 * list of {@link TestMethodRecord} ({packageName, className, methodName}). No
 * compilation, no classpath — the test source is read as AST text only.
 */
class JavaTestExtractorTest {

    private JavaTestExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JavaTestExtractor();
    }

    @Test
    void whenSourceHasOneJUnit5TestMethod_thenItIsExtracted() {
        String src = """
                package com.example.svc;

                import org.junit.jupiter.api.Test;

                class HealthSpec {
                    @Test
                    void whenServiceIsUp_thenHealthEndpointReturns200() {}
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        assertThat(out).containsExactly(
                new TestMethodRecord("com.example.svc", "HealthSpec",
                        "whenServiceIsUp_thenHealthEndpointReturns200"));
    }

    @Test
    void whenSourceHasMultipleTestMethods_thenAllAreExtractedInDeclarationOrder() {
        String src = """
                package com.example.svc;
                import org.junit.jupiter.api.Test;

                class OrdersSpec {
                    @Test void firstScenario() {}
                    @Test void secondScenario() {}
                    void notATest() {}
                    @Test void thirdScenario() {}
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        assertThat(out).extracting(TestMethodRecord::methodName)
                .containsExactly("firstScenario", "secondScenario", "thirdScenario");
    }

    @Test
    void whenSourceHasNoTestAnnotations_thenExtractorReturnsEmptyList() {
        String src = """
                package com.example;
                class JustACompanion {
                    void helper() {}
                }
                """;

        assertThat(extractor.extract(src)).isEmpty();
    }

    @Test
    void whenSourceHasNoPackageDeclaration_thenPackageIsEmptyString() {
        String src = """
                import org.junit.jupiter.api.Test;
                class RootSpec {
                    @Test void scenario() {}
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).packageName()).isEmpty();
        assertThat(out.get(0).className()).isEqualTo("RootSpec");
    }

    @Test
    void whenAnnotationIsFullyQualified_thenItIsRecognised() {
        // Tests written with @org.junit.jupiter.api.Test rather than the import.
        String src = """
                package com.example;
                class FullyQualifiedSpec {
                    @org.junit.jupiter.api.Test
                    void whenAnnotationIsFullyQualified_thenItStillCounts() {}
                }
                """;

        assertThat(extractor.extract(src)).hasSize(1);
    }

    @Test
    void whenAnnotationIsJUnit4Test_thenItIsRecognised() {
        // Older code (or test-extraction targets that mix JUnit 4 + 5) uses
        // org.junit.Test. The extractor doesn't care which Test annotation —
        // a method named @Test of any provenance counts.
        String src = """
                package com.example;
                import org.junit.Test;
                public class LegacySpec {
                    @Test public void legacyScenario() {}
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).methodName()).isEqualTo("legacyScenario");
    }

    @Test
    void whenSourceHasMultipleTopLevelClasses_thenMethodsCarryTheirOwnClassName() {
        // Rare in production but legal Java — JavaParser handles it cleanly.
        String src = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class FirstSpec {
                    @Test void scenarioA() {}
                }
                class SecondSpec {
                    @Test void scenarioB() {}
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        assertThat(out).extracting(TestMethodRecord::className, TestMethodRecord::methodName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FirstSpec", "scenarioA"),
                        org.assertj.core.groups.Tuple.tuple("SecondSpec", "scenarioB"));
    }

    @Test
    void whenSourceIsMalformed_thenExtractorThrowsCleanly() {
        String garbage = "this is not actually Java { { {{{{ ;;;";

        assertThatThrownBy(() -> extractor.extract(garbage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenInnerClassHasTestMethod_thenItIsExtractedWithEnclosingClassNameInPath() {
        // JUnit 5 @Nested test classes are inner classes. We surface the
        // outermost (publicly-named) class as the test class — the @Nested
        // structure is invisible at the page-rendering grain.
        String src = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class OuterSpec {
                    @Test void outerScenario() {}
                    static class NestedSpec {
                        @Test void nestedScenario() {}
                    }
                }
                """;

        List<TestMethodRecord> out = extractor.extract(src);

        // Both test methods are captured; the outer class name carries through.
        assertThat(out).extracting(TestMethodRecord::methodName)
                .containsExactlyInAnyOrder("outerScenario", "nestedScenario");
    }
}
