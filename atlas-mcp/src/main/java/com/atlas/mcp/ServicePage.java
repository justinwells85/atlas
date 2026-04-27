package com.atlas.mcp;

import java.util.List;

public record ServicePage(
        int page,
        int pageSize,
        long totalCount,
        List<ServiceSummary> services) {
}
