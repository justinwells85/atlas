package com.atlas.intake;

import java.util.UUID;

/**
 * In-progress representation of one row in {@code api_consumers} — a service
 * that calls a particular API endpoint exposed by the service being created.
 * The {@code consumerServiceId} is resolved from the user-provided name at
 * capture time so persistAndComplete doesn't need to do a second lookup.
 */
public record ApiConsumerDraft(UUID consumerServiceId, String consumerServiceName, String description) {

    public static ApiConsumerDraft empty() {
        return new ApiConsumerDraft(null, null, null);
    }

    public ApiConsumerDraft withConsumer(UUID id, String name) {
        return new ApiConsumerDraft(id, name, description);
    }

    public ApiConsumerDraft withDescription(String v) {
        return new ApiConsumerDraft(consumerServiceId, consumerServiceName, v);
    }
}
