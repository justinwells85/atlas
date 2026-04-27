package com.atlas.services;

import java.time.OffsetDateTime;

public record ChangeEntry(
        OffsetDateTime changedAt,
        String changedBy,
        String changeType,
        String summary) {
}
