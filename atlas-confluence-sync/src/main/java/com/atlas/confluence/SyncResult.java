package com.atlas.confluence;

import java.util.List;

public record SyncResult(
        int successCount,
        int failureCount,
        List<SyncFailure> failures) {
}
