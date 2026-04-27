package com.atlas.confluence;

import java.util.UUID;

public record SyncFailure(
        UUID serviceId,
        String serviceName,
        String message) {
}
