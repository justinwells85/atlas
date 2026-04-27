package com.atlas.services;

import java.util.UUID;

public record ApiConsumer(
        UUID consumerServiceId,
        String consumerServiceName,
        String description) {
}
