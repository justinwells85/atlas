package com.atlas.confluence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic Confluence sync. Thin wrapper over {@link SyncCoordinator#syncAll()};
 * the same coordinator is also driven manually via the REST controller. Cron
 * interval is property-driven ({@code atlas.confluence.sync.cron}, default
 * every 15 minutes).
 *
 * Scheduling itself is enabled by {@code @EnableScheduling} on
 * {@link AtlasConfluenceSyncApplication}.
 */
@Component
public class ScheduledSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncJob.class);

    private final SyncCoordinator coordinator;

    public ScheduledSyncJob(SyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${atlas.confluence.sync.cron}")
    public void runScheduled() {
        log.info("Scheduled Confluence sync starting");
        SyncResult result = coordinator.syncAll();
        log.info("Scheduled Confluence sync complete: {} success, {} failure",
                result.successCount(), result.failureCount());
    }
}
