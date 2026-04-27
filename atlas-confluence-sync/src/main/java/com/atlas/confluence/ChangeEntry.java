package com.atlas.confluence;

import java.time.OffsetDateTime;

public record ChangeEntry(
        OffsetDateTime changedAt,
        String changedBy,
        String changeType,
        String summary) {
}
