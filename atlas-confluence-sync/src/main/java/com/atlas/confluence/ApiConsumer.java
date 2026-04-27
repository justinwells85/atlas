package com.atlas.confluence;

import java.util.UUID;

public record ApiConsumer(
        UUID consumerServiceId,
        String consumerServiceName,
        String description) {
}
