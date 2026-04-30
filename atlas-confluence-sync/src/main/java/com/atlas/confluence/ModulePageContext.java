package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceModule;

import java.util.List;
import java.util.Map;

/**
 * Pre-loaded inputs for rendering one L4 module Confluence page (Phase 5.6 M2).
 *
 * <p>{@code allModules} carries every live module observation for the
 * service so the renderer can walk the parent/children relationships
 * itself rather than the loader pre-computing sub-records. Same idea as
 * {@link ServicePageContext} carrying the full APIs list rather than
 * pre-grouped views.
 *
 * <p>{@code moduleConfluencePageUrls} maps each module's {@code module_path}
 * to its Confluence page URL. Modules whose page hasn't been created yet
 * (first sync round, or sub-module discovered after the page-create pass)
 * have a null entry — the renderer falls back to plain text so the page
 * still renders, and the next sync round picks up the link.
 */
public record ModulePageContext(
        Service service,
        ServiceModule module,
        List<ServiceModule> allModules,
        Map<String, String> moduleConfluencePageUrls,
        String serviceConfluenceUrl) {
}
