package com.atlas.codesync;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.javadoc.Javadoc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure AST extractor (Phase 5.6 M3 — L5 drill-down): given one Java
 * source file's text, returns a list of {@link BeanRecord} for every
 * top-level class annotated with one of the narrow Spring stereotypes
 * (per the Phase 5.6 plan, open question 1, Option A):
 *
 * <ul>
 *   <li>{@code @RestController}, {@code @Controller}</li>
 *   <li>{@code @Service}, {@code @Repository}</li>
 *   <li>{@code @Component}, {@code @Configuration}</li>
 * </ul>
 *
 * <p>Excluded by design: DTOs, POJOs, enums, JPA {@code @Entity}, Spring
 * Data repository interfaces, {@code @ConfigurationProperties}. The Beans
 * page is an architectural-seam index, not a class catalogue.
 *
 * <p>Mirrors {@link JavaTestExtractor}: source-level AST only, no
 * compilation, no classpath. Top-level classes only — nested stereotype
 * classes (a rarely-useful pattern) are not surfaced.
 */
@Component
public class JavaBeanExtractor {

    private static final Set<String> STEREOTYPES = Set.of(
            "RestController",
            "Controller",
            "Service",
            "Repository",
            "Component",
            "Configuration");

    // Pin the parser to JAVA_21 — Atlas's tech stack is Java 21 LTS and
    // real services use records, switch expressions, sealed types, etc.
    // Default JavaParser config rejects everything past Java 8, so the
    // dogfood was silently dropping ~12 stereotype classes per refresh.
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

    public List<BeanRecord> extract(String javaSource) {
        ParseResult<CompilationUnit> parseResult = parser.parse(javaSource);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalArgumentException(
                    "Java source could not be parsed: " + parseResult.getProblems());
        }
        CompilationUnit cu = parseResult.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getName().asString())
                .orElse("");

        List<BeanRecord> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            // Top-level only — nested stereotype classes (rare in practice)
            // are skipped. JavaParser tracks nesting via the parent node.
            if (cls.getParentNode().isPresent()
                    && cls.getParentNode().get() instanceof ClassOrInterfaceDeclaration) {
                continue;
            }
            // Spring stereotypes don't apply to interfaces (Spring Data
            // repository interfaces use an unrelated mechanism). Skip.
            if (cls.isInterface()) continue;

            String stereotype = pickStereotype(cls);
            if (stereotype == null) continue;

            String classJavadoc = cls.getJavadoc()
                    .map(JavaBeanExtractor::firstSentence)
                    .orElse(null);

            List<BeanRecord.MethodRecord> methods = new ArrayList<>();
            for (MethodDeclaration method : cls.getMethods()) {
                if (!method.hasModifier(Modifier.Keyword.PUBLIC)) continue;
                String signature = renderSignature(method);
                String methodJavadoc = method.getJavadoc()
                        .map(JavaBeanExtractor::firstSentence)
                        .orElse(null);
                methods.add(new BeanRecord.MethodRecord(
                        method.getNameAsString(),
                        signature,
                        methodJavadoc));
            }

            out.add(new BeanRecord(
                    packageName,
                    cls.getNameAsString(),
                    stereotype,
                    classJavadoc,
                    methods));
        }
        return out;
    }

    /**
     * Return the simple name of the first stereotype annotation found on
     * the class, or null if none are present. Annotation order matches
     * declaration order in source — so {@code @RestController @Service}
     * surfaces as {@code RestController} (the more specific marker).
     */
    private static String pickStereotype(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String simple = simpleAnnotationName(ann.getNameAsString());
            if (STEREOTYPES.contains(simple)) return simple;
        }
        return null;
    }

    private static String simpleAnnotationName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }

    /**
     * Build a one-line method signature: {@code returnType name(paramType, ...)}.
     * Type names use simple names where the AST has them; fully-qualified
     * names survive only when the source declared them that way. Generics
     * are preserved as written.
     */
    private static String renderSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getType().asString()).append(" ");
        sb.append(method.getNameAsString()).append("(");
        boolean first = true;
        for (Parameter p : method.getParameters()) {
            if (!first) sb.append(", ");
            sb.append(p.getType().asString()).append(" ").append(p.getNameAsString());
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Pull the first sentence out of a Javadoc, trimmed. JavaParser already
     * exposes {@link Javadoc#getDescription()} as the prose body before
     * the first {@code @-tag}; we further chop at the first period
     * followed by whitespace or end-of-string for the "first sentence"
     * heuristic. Returns null when the result is empty.
     */
    private static String firstSentence(Javadoc javadoc) {
        String body = javadoc.getDescription().toText().strip();
        if (body.isEmpty()) return null;
        int dot = -1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '.') {
                if (i == body.length() - 1
                        || Character.isWhitespace(body.charAt(i + 1))) {
                    dot = i;
                    break;
                }
            }
        }
        if (dot < 0) return body;
        return body.substring(0, dot + 1).strip();
    }
}
