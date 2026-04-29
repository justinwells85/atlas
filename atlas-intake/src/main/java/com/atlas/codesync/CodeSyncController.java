package com.atlas.codesync;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/code-sync")
public class CodeSyncController {

    private final CodeSyncCoordinator coordinator;

    public CodeSyncController(CodeSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/refresh/{serviceId}")
    @Operation(summary = "Re-derive the OpenAPI-source apis rows for one service",
            description = "Fetches the spec at services.openapi_spec_url, parses it, and upserts apis rows tagged source='openapi' (transactional, idempotent). intake-source rows are deferred to, never overwritten. Returns counts of created/updated/deleted/skipped.")
    public CodeSyncResult refresh(@PathVariable UUID serviceId) {
        return coordinator.refreshOpenApi(serviceId);
    }

    @PostMapping("/refresh-tests/{serviceId}")
    @Operation(summary = "Re-derive the test-scenario rows for one service from its repo",
            description = "Walks {module_path}/src/test/java in the GitHub repo named on services.repo_url, extracts every @Test-annotated method, and upserts service_test_scenarios rows tagged source='tests' (transactional, idempotent). Public repos only at this stage.")
    public CodeSyncResult refreshTests(@PathVariable UUID serviceId) {
        return coordinator.refreshTests(serviceId);
    }

    @PostMapping("/refresh-pom/{serviceId}")
    @Operation(summary = "Re-derive metadata + external-dep observations from the service's pom.xml",
            description = "Fetches {module_path}/pom.xml from the GitHub repo on services.repo_url, parses the literal declarations, and appends service_metadata and service_external_deps observations tagged source='pom-xml'. Append-only — no in-place edits. Skips dependencies whose groupId is under atlas.code-sync.org-group-prefix (default com.atlas).")
    public CodeSyncResult refreshPom(@PathVariable UUID serviceId) {
        return coordinator.refreshPom(serviceId);
    }
}
