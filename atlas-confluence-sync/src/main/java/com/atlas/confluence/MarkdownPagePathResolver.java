package com.atlas.confluence;

import java.util.Locale;

/**
 * Maps an Atlas wiki page title to its vault-relative file path
 * (Phase 5.8 M3). The mapping is convention-driven and matches the
 * {@code pageTitle(...)} static helpers on every {@code *MarkdownRenderer}:
 *
 * <ul>
 *   <li>{@code "Atlas — Service Inventory"} → {@code README.md}</li>
 *   <li>{@code "Atlas — Architecture Map"} → {@code architecture-map.md}</li>
 *   <li>{@code "Inventory: Data Stores"} → {@code inventory-data-stores.md}</li>
 *   <li>{@code "Inventory: External Dependencies"} → {@code inventory-external-deps.md}</li>
 *   <li>{@code "About Atlas"} → {@code about.md}</li>
 *   <li>{@code "Service: <name>"} → {@code services/<name>/<name>.md}</li>
 *   <li>{@code "<name> — Beans"} → {@code services/<name>/beans.md}</li>
 *   <li>{@code "<name> — Tests"} → {@code services/<name>/tests.md}</li>
 *   <li>{@code "<name> — Module: <path>"} → {@code services/<name>/modules/<path>.md}</li>
 *   <li>{@code "<name> — METHOD path"} → {@code services/<name>/endpoints/<method>-<slugified-path>.md}</li>
 * </ul>
 *
 * <p>Naming the L2 service page {@code <name>.md} (not {@code README.md})
 * makes Obsidian WikiLinks {@code [[name]]} resolve unambiguously across
 * the vault. The vault root's {@code README.md} (the landing page) is the
 * only exception; Obsidian treats it specially as the home note.
 */
final class MarkdownPagePathResolver {

    private static final String LANDING = "Atlas — Service Inventory";
    private static final String ARCHITECTURE_MAP = "Atlas — Architecture Map";
    private static final String DATA_STORE_INVENTORY = "Inventory: Data Stores";
    private static final String EXTERNAL_DEP_INVENTORY = "Inventory: External Dependencies";
    private static final String ABOUT = "About Atlas";

    private static final String SERVICE_PREFIX = "Service: ";
    private static final String MODULE_INFIX = " — Module: ";
    private static final String BEANS_SUFFIX = " — Beans";
    private static final String TESTS_SUFFIX = " — Tests";

    private MarkdownPagePathResolver() {}

    /**
     * Convert a stored Markdown ref (vault-relative file path with {@code .md})
     * into the WikiLink-target form Obsidian resolves. Strips the {@code .md}
     * extension; for the L2 service-page idiom
     * {@code services/<svc>/<svc>.md} the basename is unique-by-construction
     * across the vault, so the target collapses to {@code <svc>} for the
     * short {@code [[<svc>]]} link form. Other pages whose basenames may
     * collide across services (beans, tests, modules, endpoints) keep their
     * full path-without-extension so {@code [[services/<svc>/beans]]}
     * resolves unambiguously.
     *
     * <p>Returns {@code null} for null/blank input so callers don't have to
     * pre-check.
     */
    static String wikiLinkTargetFor(String storedRef) {
        if (storedRef == null || storedRef.isBlank()) return null;
        String pathNoExt = storedRef.endsWith(".md")
                ? storedRef.substring(0, storedRef.length() - 3)
                : storedRef;
        int lastSlash = pathNoExt.lastIndexOf('/');
        if (lastSlash > 0) {
            String parent = pathNoExt.substring(0, lastSlash);
            String basename = pathNoExt.substring(lastSlash + 1);
            // services/<svc>/<svc> → <svc>
            if (parent.equals("services/" + basename)) {
                return basename;
            }
        }
        return pathNoExt;
    }

    /**
     * Resolve a title to its vault-relative path. Returns the path with
     * forward slashes (Obsidian and the JVM both handle these on every
     * platform we target).
     */
    static String pathFor(String title) {
        if (title == null) throw new IllegalArgumentException("title is required");

        switch (title) {
            case LANDING -> { return "README.md"; }
            case ARCHITECTURE_MAP -> { return "architecture-map.md"; }
            case DATA_STORE_INVENTORY -> { return "inventory-data-stores.md"; }
            case EXTERNAL_DEP_INVENTORY -> { return "inventory-external-deps.md"; }
            case ABOUT -> { return "about.md"; }
        }

        if (title.startsWith(SERVICE_PREFIX)) {
            String name = title.substring(SERVICE_PREFIX.length());
            String safe = slug(name);
            return "services/" + safe + "/" + safe + ".md";
        }

        // Service-scoped pages: "<service> — <suffix>"
        int dash = title.indexOf(" — ");
        if (dash > 0) {
            String serviceName = title.substring(0, dash);
            String rest = title.substring(dash + " — ".length());
            String serviceSlug = slug(serviceName);

            if (title.endsWith(BEANS_SUFFIX)) {
                return "services/" + serviceSlug + "/beans.md";
            }
            if (title.endsWith(TESTS_SUFFIX)) {
                return "services/" + serviceSlug + "/tests.md";
            }
            if (rest.startsWith("Module: ")) {
                String modulePath = rest.substring("Module: ".length());
                String moduleSlug = slug(stripParenthesisedRoot(modulePath));
                return "services/" + serviceSlug + "/modules/" + moduleSlug + ".md";
            }
            // Endpoint: "METHOD path" — first whitespace-separated token is
            // the HTTP method, the rest is the path. Build a slug like
            // "post-api-intake-turn".
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                String method = rest.substring(0, sp);
                String path = rest.substring(sp + 1);
                return "services/" + serviceSlug + "/endpoints/"
                        + slug(method) + "-" + slugifyPath(path) + ".md";
            }
        }

        // Fallback — slug the whole title at the vault root.
        return slug(title) + ".md";
    }

    /**
     * Lowercase + replace runs of non-{@code [a-z0-9]} with single hyphens,
     * trim leading/trailing hyphens. The output is filename- and
     * WikiLink-safe across platforms.
     */
    private static String slug(String s) {
        if (s == null || s.isBlank()) return "untitled";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevHyphen = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevHyphen = false;
            } else {
                if (!prevHyphen) {
                    sb.append('-');
                    prevHyphen = true;
                }
            }
        }
        // Trim trailing hyphen.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "untitled" : sb.toString();
    }

    /**
     * Slug a URL path. {@code /v1/orders} → {@code v1-orders};
     * {@code /users/{id}/posts} → {@code users-id-posts}. Curly braces and
     * slashes both collapse into the slug separator.
     */
    private static String slugifyPath(String path) {
        return slug(path);
    }

    /** {@code "(root)"} → {@code "root"}; otherwise unchanged. */
    private static String stripParenthesisedRoot(String s) {
        if ("(root)".equals(s)) return "root";
        return s;
    }
}
