package com.atlas.confluence;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;

/**
 * Pre-loaded inputs for rendering one per-endpoint Confluence page (M2).
 *
 * {@code serviceConfluenceUrl} is the URL of the parent service page, used to
 * render a back-link. May be null if the service hasn't been synced yet — the
 * renderer falls back to plain text in that case (eventual consistency: the
 * next sync round picks up the link).
 */
public record ApiEndpointPageContext(
        Service service,
        ApiSummary api,
        String serviceConfluenceUrl) {
}
