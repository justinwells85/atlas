package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceBean;

import java.util.List;

/**
 * Pre-loaded inputs for rendering one per-service Beans Confluence page
 * (Phase 5.6 M3 — L5 of the drill-down).
 *
 * <p>The Beans page is always rendered (even when {@code beans} is empty)
 * so the sidebar tree stays consistent — same convention as the Tests
 * page. An empty Beans list renders a thin "no beans documented" note
 * rather than producing no page at all.
 *
 * <p>{@code serviceConfluenceUrl} is the parent service page URL for the
 * back-link.
 */
public record BeansPageContext(
        Service service,
        List<ServiceBean> beans,
        String serviceConfluenceUrl) {
}
