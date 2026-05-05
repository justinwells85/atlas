package com.atlas.codesync;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure AST extractor (Phase 5.9 M3 — configuration extraction): given one
 * Java source file's text, returns every {@code @Enable*}-prefixed
 * annotation use-site found on a top-level class annotated with
 * {@code @Configuration} or {@code @SpringBootApplication}.
 *
 * <p>Mirrors {@link JavaBeanExtractor} / {@link JavaConfigurationExtractor}:
 * source-level only, no compilation, no classpath, JavaParser pinned to
 * JAVA_21.
 *
 * <p>The filter is lexical — any annotation whose simple name starts with
 * {@code "Enable"} qualifies. Spring's built-in {@code @Enable*}
 * annotations and org-internal annotations following the same convention
 * are both captured. We do not follow meta-annotations or compute the
 * actual subsystem activation graph; surfacing what the source declares
 * is the load-bearing data for an ownership reader.
 *
 * <p>FQN resolution uses the source file's import statements: a direct
 * import gives the FQN exactly; a fully-qualified annotation use-site
 * (rare, e.g. {@code @org.springframework.scheduling.annotation.EnableScheduling})
 * is taken verbatim; otherwise the simple name is the fallback FQN.
 * Wildcard imports and same-package usage produce the simple-name fallback.
 */
@Component
public class JavaEnableAnnotationExtractor {

    private static final String CONFIGURATION = "Configuration";
    private static final String SPRING_BOOT_APPLICATION = "SpringBootApplication";
    private static final String ENABLE_PREFIX = "Enable";

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

    public List<EnableAnnotationRecord> extract(String javaSource) {
        ParseResult<CompilationUnit> parseResult = parser.parse(javaSource);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalArgumentException(
                    "Java source could not be parsed: " + parseResult.getProblems());
        }
        CompilationUnit cu = parseResult.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getName().asString())
                .orElse("");

        // Index imports by simple name for FQN resolution.
        Map<String, String> importsBySimpleName = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) continue;
            String full = imp.getNameAsString();
            int dot = full.lastIndexOf('.');
            String simple = dot < 0 ? full : full.substring(dot + 1);
            importsBySimpleName.put(simple, full);
        }

        List<EnableAnnotationRecord> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            // Top-level only — nested @Configuration classes are skipped.
            if (cls.getParentNode().isPresent()
                    && cls.getParentNode().get() instanceof ClassOrInterfaceDeclaration) {
                continue;
            }
            if (cls.isInterface()) continue;
            if (!hasConfigurationOrSpringBootApplication(cls)) continue;

            String fqn = packageName.isEmpty()
                    ? cls.getNameAsString()
                    : packageName + "." + cls.getNameAsString();

            for (AnnotationExpr ann : cls.getAnnotations()) {
                String useSiteName = ann.getNameAsString();
                String simple = simpleAnnotationName(useSiteName);
                if (!simple.startsWith(ENABLE_PREFIX)) continue;

                String resolvedFqn;
                if (useSiteName.contains(".")) {
                    // Fully-qualified at the use-site.
                    resolvedFqn = useSiteName;
                } else if (importsBySimpleName.containsKey(simple)) {
                    // Direct import gives the canonical FQN.
                    resolvedFqn = importsBySimpleName.get(simple);
                } else if (!packageName.isEmpty()) {
                    // Not imported and not fully-qualified → assume same-package.
                    // This is the standard Java resolution rule and the right
                    // default for org-internal @Enable* annotations declared
                    // alongside their @Configuration use sites.
                    resolvedFqn = packageName + "." + simple;
                } else {
                    resolvedFqn = simple;
                }
                out.add(new EnableAnnotationRecord(fqn, simple, resolvedFqn));
            }
        }
        return out;
    }

    private static boolean hasConfigurationOrSpringBootApplication(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String simple = simpleAnnotationName(ann.getNameAsString());
            if (CONFIGURATION.equals(simple) || SPRING_BOOT_APPLICATION.equals(simple)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleAnnotationName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }
}
