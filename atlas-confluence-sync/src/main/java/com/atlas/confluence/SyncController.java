package com.atlas.confluence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Manual sync trigger. Two endpoints, both POST (sync is a state-changing
 * action, not a read).
 *
 * <ul>
 *   <li>{@code POST /api/sync/run} — sync every service in the DB.</li>
 *   <li>{@code POST /api/sync/run/{serviceId}} — sync one service by UUID.</li>
 * </ul>
 *
 * The same coordinator powers the (eventual M5) scheduled job; this controller
 * exists for ad-hoc runs during development and for the M4 real-instance smoke.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncCoordinator coordinator;

    public SyncController(SyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/run")
    public SyncResult runAll() {
        return coordinator.syncAll();
    }

    @PostMapping("/run/{serviceId}")
    public SyncResult runOne(@PathVariable UUID serviceId) {
        return coordinator.syncOne(serviceId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleUnknownService(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
