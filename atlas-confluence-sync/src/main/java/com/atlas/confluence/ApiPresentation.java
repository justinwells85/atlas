package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ApiSummary;

import java.util.List;

/**
 * One row of the service page's APIs section. {@code endpointPageUrl} is the
 * Confluence URL of the per-endpoint page (M2). Null when the endpoint has not
 * yet been synced to its own page; the renderer falls back to plain text in
 * that case.
 */
public record ApiPresentation(
        ApiSummary api,
        List<ApiConsumer> consumers,
        String endpointPageUrl) {
}
