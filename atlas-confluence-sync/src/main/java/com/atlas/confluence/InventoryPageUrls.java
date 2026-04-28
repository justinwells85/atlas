package com.atlas.confluence;

/**
 * Confluence URLs of the inventory sub-pages, threaded through to
 * {@link ServicePageRenderer} so per-service Database and External-Dependency
 * sections can back-link into the inventory pages.
 *
 * Either or both fields may be {@code null} — when null, the renderer falls
 * back to plain text (no back-link). All-null is acceptable on the very
 * first sync before the inventory pages have been ensured.
 */
public record InventoryPageUrls(String dataStores, String externalDependencies) {

    public static InventoryPageUrls empty() {
        return new InventoryPageUrls(null, null);
    }
}
