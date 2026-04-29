package com.atlas.codesync;

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
    public CodeSyncResult refresh(@PathVariable UUID serviceId) {
        return coordinator.refreshOpenApi(serviceId);
    }
}
