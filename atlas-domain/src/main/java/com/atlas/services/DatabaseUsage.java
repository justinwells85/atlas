package com.atlas.services;

import java.util.UUID;

public record DatabaseUsage(
        UUID databaseId,
        String databaseName,
        String engine,
        boolean isOwner,
        String description) {
}
