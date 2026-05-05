package com.atlas.codesync;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-function parser for Spring Boot configuration files (Phase 5.9 M1).
 *
 * <p>{@link #parse(String, String)} routes by filename extension. Properties
 * files use {@link java.util.Properties} (handles comments, line continuations,
 * escapes per the JDK contract). YAML files use SnakeYAML's {@code SafeConstructor}
 * — multi-document files iterate all documents, nested maps flatten with
 * dot-joined keys, scalar values stringify, null/missing scalars become
 * the empty string.
 *
 * <p>Profile is derived from the filename: {@code "default"} for plain
 * {@code application.{ext}}; the captured suffix for
 * {@code application-{profile}.{ext}}. Compound profile suffixes
 * (e.g. {@code application-prod-eu.properties}) carry through as-is.
 *
 * <p>Unrecognised filenames return an empty list — this is the parser's
 * "this isn't a config file I know about" signal; the coordinator is
 * responsible for filtering before calling.
 */
@Component
public class PropertiesFileParser {

    private static final Pattern FILENAME = Pattern.compile(
            "^application(?:-([^.]+))?\\.(properties|yml|yaml)$");

    public List<PropertyEntry> parse(String content, String filename) {
        Matcher m = FILENAME.matcher(filename);
        if (!m.matches()) {
            return List.of();
        }
        String profile = m.group(1) == null ? "default" : m.group(1);
        String extension = m.group(2);

        if ("properties".equals(extension)) {
            return parseProperties(content, profile);
        }
        return parseYaml(content, profile);
    }

    private List<PropertyEntry> parseProperties(String content, String profile) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (java.io.IOException e) {
            // StringReader doesn't actually throw — keep the static-analysis
            // happy without inventing a meaningful failure mode.
            throw new IllegalStateException("properties parse failed", e);
        }
        List<PropertyEntry> out = new ArrayList<>(props.size());
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            out.add(new PropertyEntry(key, value == null ? "" : value, profile));
        }
        return out;
    }

    private List<PropertyEntry> parseYaml(String content, String profile) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<PropertyEntry> out = new ArrayList<>();
        for (Object document : yaml.loadAll(content)) {
            if (document == null) {
                continue;
            }
            if (!(document instanceof Map<?, ?> map)) {
                // Top-level scalar / list — not a Spring Boot config shape;
                // skip silently. The plan's "missing-file tolerance" extends
                // to malformed-but-parseable inputs.
                continue;
            }
            flatten("", map, profile, out);
        }
        return out;
    }

    private void flatten(String prefix, Map<?, ?> map, String profile, List<PropertyEntry> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(path, nested, profile, out);
            } else {
                out.add(new PropertyEntry(path, value == null ? "" : String.valueOf(value), profile));
            }
        }
    }
}
