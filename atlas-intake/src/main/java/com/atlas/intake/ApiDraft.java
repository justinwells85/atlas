package com.atlas.intake;

import java.util.ArrayList;
import java.util.List;

/**
 * In-progress representation of one API endpoint being captured during the
 * interview. Maps 1:1 onto a row in the {@code apis} table at persist time.
 * Carries a list of {@link ApiConsumerDraft}s gathered in the per-API
 * consumers sub-loop; each becomes a row in {@code api_consumers}.
 */
public record ApiDraft(
        String path,
        String method,
        String authMethod,
        String description,
        List<ApiConsumerDraft> consumers) {

    public ApiDraft {
        if (consumers == null) consumers = List.of();
    }

    public static ApiDraft empty() {
        return new ApiDraft(null, null, null, null, List.of());
    }

    public ApiDraft withPath(String v) { return new ApiDraft(v, method, authMethod, description, consumers); }
    public ApiDraft withMethod(String v) { return new ApiDraft(path, v, authMethod, description, consumers); }
    public ApiDraft withAuthMethod(String v) { return new ApiDraft(path, method, v, description, consumers); }
    public ApiDraft withDescription(String v) { return new ApiDraft(path, method, authMethod, v, consumers); }

    public ApiDraft withAddedConsumer(ApiConsumerDraft consumer) {
        List<ApiConsumerDraft> next = new ArrayList<>(consumers);
        next.add(consumer);
        return new ApiDraft(path, method, authMethod, description, List.copyOf(next));
    }
}
