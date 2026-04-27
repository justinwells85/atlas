package com.atlas.confluence;

import com.atlas.services.ApiSummary;

import java.util.List;

public record ApiPresentation(
        ApiSummary api,
        List<ApiConsumer> consumers) {
}
