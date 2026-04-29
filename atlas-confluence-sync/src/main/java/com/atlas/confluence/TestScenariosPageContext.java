package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.TestScenario;

import java.util.List;

/**
 * Inputs for the per-service tests-page renderer (M3). {@code scenarios} is
 * pre-loaded by the sync coordinator; the renderer is pure. Caller orders
 * scenarios in render order — typically by package, class, method.
 *
 * {@code serviceConfluenceUrl} is the URL of the parent service page, used
 * to render a back-link. May be null if the service has not been synced yet;
 * the renderer falls back to omitting the back-link.
 */
public record TestScenariosPageContext(
        Service service,
        List<TestScenario> scenarios,
        String serviceConfluenceUrl) {
}
