package com.atlas.codesync;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure AST extractor (Phase 5.9 M2 — configuration extraction): given one
 * Java source file's text, returns a {@link ConfigurationExtractionResult}
 * containing all {@code @Value} injection sites plus all
 * {@code @ConfigurationProperties}-annotated types in the file. Mirrors
 * {@link JavaBeanExtractor}: source-level AST only, no compilation, no
 * classpath, JavaParser pinned to JAVA_21.
 *
 * <p>{@code @Value} sites are recognised on field declarations, constructor
 * parameters, and method parameters. {@code @ConfigurationProperties} is
 * recognised on top-level classes and records.
 *
 * <p>SpEL parsing is naive — a regex matches the simple
 * dollar-brace-key-colon-default shape. Expressions that don't match
 * (plain literals, nested SpEL) are surfaced verbatim with empty
 * {@code keyPath} per the malformed-SpEL tolerance in the plan.
 */
@Component
public class JavaConfigurationExtractor {

    private static final String VALUE_ANNOTATION = "Value";
    private static final String CONFIG_PROPS_ANNOTATION = "ConfigurationProperties";

    // Match dollar-brace-key-(:default)-close-brace. The key is greedy until
    // the first colon or close-brace; the default (after colon) is optional.
    private static final Pattern SPEL_PATTERN = Pattern.compile(
            "^\\$\\{([^:}]+)(?::(.*))?}$");

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

    public ConfigurationExtractionResult extract(String javaSource) {
        ParseResult<CompilationUnit> parseResult = parser.parse(javaSource);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            throw new IllegalArgumentException(
                    "Java source could not be parsed: " + parseResult.getProblems());
        }
        CompilationUnit cu = parseResult.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getName().asString())
                .orElse("");

        List<ValueInjectionRecord> valueInjections = new ArrayList<>();
        List<ConfigurationPropertiesTypeRecord> configPropsTypes = new ArrayList<>();

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            // Top-level types only — nested config classes are uncommon
            // and we follow the JavaBeanExtractor precedent.
            if (type.getParentNode().isPresent()
                    && type.getParentNode().get() instanceof TypeDeclaration<?>) {
                continue;
            }
            String fqn = packageName.isEmpty()
                    ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();

            extractValueInjections(type, fqn, valueInjections);

            findConfigPropertiesAnnotation(type)
                    .ifPresent(ann -> configPropsTypes.add(buildConfigPropsRecord(type, fqn, ann)));
        }

        return new ConfigurationExtractionResult(valueInjections, configPropsTypes);
    }

    // --- @Value extraction --------------------------------------------------

    private static void extractValueInjections(TypeDeclaration<?> type, String fqn,
                                                List<ValueInjectionRecord> out) {
        // Fields
        for (FieldDeclaration field : type.findAll(FieldDeclaration.class)) {
            valueAnnotationOf(field).ifPresent(spel -> {
                for (VariableDeclarator var : field.getVariables()) {
                    out.add(buildValueRecord(fqn, var.getNameAsString(), "field", spel));
                }
            });
        }
        // Constructor parameters
        for (ConstructorDeclaration ctor : type.findAll(ConstructorDeclaration.class)) {
            for (Parameter p : ctor.getParameters()) {
                valueAnnotationOf(p).ifPresent(spel ->
                        out.add(buildValueRecord(fqn, p.getNameAsString(),
                                "constructor-parameter", spel)));
            }
        }
        // Method parameters
        for (MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
            for (Parameter p : method.getParameters()) {
                valueAnnotationOf(p).ifPresent(spel ->
                        out.add(buildValueRecord(fqn, p.getNameAsString(),
                                "method-parameter", spel)));
            }
        }
    }

    private static java.util.Optional<String> valueAnnotationOf(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            if (!VALUE_ANNOTATION.equals(simpleAnnotationName(ann.getNameAsString()))) continue;
            return stringLiteralOfSingleMember(ann);
        }
        return java.util.Optional.empty();
    }

    private static ValueInjectionRecord buildValueRecord(String fqn, String memberName,
                                                          String memberKind, String rawSpel) {
        Matcher m = SPEL_PATTERN.matcher(rawSpel);
        if (m.matches()) {
            return new ValueInjectionRecord(fqn, memberName, memberKind,
                    rawSpel, m.group(1), m.group(2));
        }
        // Malformed-SpEL tolerance: surface verbatim with empty key, no default.
        return new ValueInjectionRecord(fqn, memberName, memberKind,
                rawSpel, "", null);
    }

    // --- @ConfigurationProperties extraction --------------------------------

    private static java.util.Optional<AnnotationExpr> findConfigPropertiesAnnotation(TypeDeclaration<?> type) {
        for (AnnotationExpr ann : type.getAnnotations()) {
            if (CONFIG_PROPS_ANNOTATION.equals(simpleAnnotationName(ann.getNameAsString()))) {
                return java.util.Optional.of(ann);
            }
        }
        return java.util.Optional.empty();
    }

    private static ConfigurationPropertiesTypeRecord buildConfigPropsRecord(TypeDeclaration<?> type,
                                                                              String fqn,
                                                                              AnnotationExpr ann) {
        String prefix = extractPrefix(ann);
        String typeKind;
        List<ConfigurationPropertiesTypeRecord.Component> components = new ArrayList<>();

        if (type instanceof RecordDeclaration record) {
            typeKind = "record";
            for (Parameter p : record.getParameters()) {
                components.add(new ConfigurationPropertiesTypeRecord.Component(
                        p.getNameAsString(), p.getType().asString()));
            }
        } else if (type instanceof ClassOrInterfaceDeclaration cls && !cls.isInterface()) {
            typeKind = "class";
            for (FieldDeclaration field : cls.getFields()) {
                if (field.isStatic()) continue;
                for (VariableDeclarator var : field.getVariables()) {
                    components.add(new ConfigurationPropertiesTypeRecord.Component(
                            var.getNameAsString(), field.getElementType().asString()));
                }
            }
        } else {
            // Interface or enum annotated with @ConfigurationProperties — rare
            // and Spring Boot won't bind to it. Capture as 'interface' so the
            // row exists for the renderer.
            typeKind = "interface";
        }

        return new ConfigurationPropertiesTypeRecord(fqn, prefix, typeKind, components);
    }

    private static String extractPrefix(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            // @ConfigurationProperties("atlas")
            Expression e = single.getMemberValue();
            if (e instanceof StringLiteralExpr s) return s.asString();
        } else if (ann instanceof NormalAnnotationExpr normal) {
            // @ConfigurationProperties(prefix = "atlas")
            for (MemberValuePair pair : normal.getPairs()) {
                if ("prefix".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                    if (pair.getValue() instanceof StringLiteralExpr s) return s.asString();
                }
            }
        }
        // MarkerAnnotationExpr (@ConfigurationProperties) → no prefix.
        return "";
    }

    // --- helpers ------------------------------------------------------------

    private static java.util.Optional<String> stringLiteralOfSingleMember(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single
                && single.getMemberValue() instanceof StringLiteralExpr s) {
            return java.util.Optional.of(s.asString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString())
                        && pair.getValue() instanceof StringLiteralExpr s) {
                    return java.util.Optional.of(s.asString());
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static String simpleAnnotationName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }
}
