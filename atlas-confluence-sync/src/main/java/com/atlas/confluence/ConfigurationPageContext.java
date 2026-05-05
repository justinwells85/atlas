package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceConfigProperty;
import com.atlas.services.ServiceConfigurationPropertiesType;
import com.atlas.services.ServiceEnableAnnotation;
import com.atlas.services.ServiceValueInjection;

import java.util.List;

/**
 * Pre-loaded inputs for rendering one per-service Configuration wiki page
 * (Phase 5.9 M4 — closes the configuration-extraction phase).
 *
 * <p>The Configuration page is always rendered (even when every list is
 * empty) so the sidebar tree stays consistent — same convention as the
 * Beans / Tests pages. An all-empty context renders a thin "no
 * configuration captured" note.
 *
 * <p>{@code serviceConfluenceUrl} is the parent service-page URL for the
 * back-link; the Markdown renderer ignores it and uses an Obsidian
 * WikiLink target instead.
 *
 * <p>The context carries the four data types extracted by
 * {@code refreshConfiguration} side-by-side so the renderer can compose a
 * single page that walks them in display order. Cross-link logic
 * (matching {@code @Value} sites against properties rows on
 * {@code keyPath}) is computed in the renderer at format time, not here —
 * keeps the context a passive bag of data.
 */
public record ConfigurationPageContext(
        Service service,
        List<ServiceConfigProperty> properties,
        List<ServiceValueInjection> valueInjections,
        List<ServiceConfigurationPropertiesType> configurationPropertiesTypes,
        List<ServiceEnableAnnotation> enableAnnotations,
        String serviceConfluenceUrl) {
}
