package com.atlas.codesync;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure AST extractor (M3 — code-driven docs): given one Java source file's
 * text, returns a list of {@link TestMethodRecord} for every
 * {@code @Test}-annotated method (JUnit 4 or 5; the annotation's simple
 * name is what's matched, so anything spelled {@code @Test} counts).
 *
 * Nested classes (e.g., JUnit 5 {@code @Nested}) contribute their own test
 * methods using their own simple name; the outer class is not prepended.
 * That keeps the page-rendering grain consistent: each row is one
 * scenario, owned by its declaring class.
 *
 * No compilation, no classpath needed — JavaParser reads source-level AST
 * only — so the extractor works against any target service's tree.
 */
@Component
public class JavaTestExtractor {

    private final JavaParser parser = new JavaParser();

    public List<TestMethodRecord> extract(String javaSource) {
        ParseResult<CompilationUnit> parseResult = parser.parse(javaSource);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalArgumentException(
                    "Java source could not be parsed: " + parseResult.getProblems());
        }
        CompilationUnit cu;
        try {
            cu = parseResult.getResult().get();
        } catch (ParseProblemException e) {
            throw new IllegalArgumentException("Java source could not be parsed", e);
        }
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getName().asString())
                .orElse("");

        List<TestMethodRecord> out = new ArrayList<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (!hasTestAnnotation(method)) continue;
            Optional<ClassOrInterfaceDeclaration> enclosing =
                    method.findAncestor(ClassOrInterfaceDeclaration.class);
            if (enclosing.isEmpty()) continue;
            out.add(new TestMethodRecord(
                    packageName,
                    enclosing.get().getNameAsString(),
                    method.getNameAsString()));
        }
        return out;
    }

    private static boolean hasTestAnnotation(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String simple = simpleAnnotationName(ann.getNameAsString());
            if ("Test".equals(simple)) return true;
        }
        return false;
    }

    private static String simpleAnnotationName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }
}
