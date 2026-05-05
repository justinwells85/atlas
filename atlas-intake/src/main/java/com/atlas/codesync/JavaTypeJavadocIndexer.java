package com.atlas.codesync;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an FQN → first-sentence-javadoc index across a list of fetched
 * source files. Used by Phase 5.9 M3 to attach javadoc summaries to
 * {@code @Enable*} annotation observations when the annotation's source
 * is reachable in the same module's source tree (Spring's built-ins live
 * in JARs and are not indexable; org-internal annotations sometimes are).
 *
 * <p>Walks every top-level {@link TypeDeclaration} (class, interface,
 * record, enum, annotation) in each source file. Returns only the entries
 * with a non-empty class-level javadoc first sentence — types without
 * javadoc are silently absent from the map. Per-file parse failures are
 * skipped (matches the {@code refreshBeans} tolerance pattern).
 */
@Component
public class JavaTypeJavadocIndexer {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

    public Map<String, String> indexFirstSentencesByFqn(List<RepoFile> sources) {
        Map<String, String> out = new HashMap<>();
        for (RepoFile f : sources) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(f.content());
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;
                CompilationUnit cu = result.getResult().get();
                String pkg = cu.getPackageDeclaration()
                        .map(p -> p.getName().asString())
                        .orElse("");
                for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                    // Top-level only — nested types' javadocs aren't useful for
                    // FQN-keyed lookup since the FQN of a nested type uses '$'
                    // by JLS but that's a runtime convention, not source.
                    if (type.getParentNode().isPresent()
                            && type.getParentNode().get() instanceof TypeDeclaration<?>) {
                        continue;
                    }
                    String fqn = pkg.isEmpty()
                            ? type.getNameAsString()
                            : pkg + "." + type.getNameAsString();
                    String firstSentence = type.getJavadoc()
                            .map(JavaTypeJavadocIndexer::firstSentence)
                            .orElse(null);
                    if (firstSentence != null && !firstSentence.isEmpty()) {
                        out.put(fqn, firstSentence);
                    }
                }
            } catch (Exception e) {
                // Skip unparseable; the rest of the index is still useful.
            }
        }
        return out;
    }

    /**
     * First sentence of a Javadoc, trimmed. Mirrors the heuristic used by
     * {@code JavaBeanExtractor.firstSentence}: take the description body,
     * chop at the first period followed by whitespace or end-of-string.
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
