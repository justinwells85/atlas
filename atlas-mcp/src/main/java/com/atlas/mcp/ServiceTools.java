package com.atlas.mcp;

import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ServiceTools {

    private static final int SEARCH_RESULT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ServiceRepository services;
    private final ServiceRelationshipsRepository relationships;

    public ServiceTools(ServiceRepository services, ServiceRelationshipsRepository relationships) {
        this.services = services;
        this.relationships = relationships;
    }

    @Tool(description = "Search services by case-insensitive substring match on the service name. Returns up to "
            + SEARCH_RESULT_LIMIT + " matching services with id, name, owner team, and status.")
    public List<ServiceSummary> searchServices(
            @ToolParam(description = "Substring to match against service names. An empty string matches nothing.")
            String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return services.findByNameContainingIgnoreCaseOrderByName(
                        query.trim(), PageRequest.of(0, SEARCH_RESULT_LIMIT))
                .stream()
                .map(ServiceTools::toSummary)
                .toList();
    }

    @Tool(description = "List services in name order, paginated. Defaults: page=0, pageSize=" + DEFAULT_PAGE_SIZE
            + ". Maximum pageSize is " + MAX_PAGE_SIZE + ".")
    public ServicePage listServices(
            @ToolParam(required = false, description = "Zero-based page index. Defaults to 0.")
            Integer page,
            @ToolParam(required = false, description = "Number of services per page. Defaults to "
                    + DEFAULT_PAGE_SIZE + "; capped at " + MAX_PAGE_SIZE + ".")
            Integer pageSize) {
        int p = (page == null || page < 0) ? 0 : page;
        int s = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        var pageResult = services.findAll(PageRequest.of(p, s,
                org.springframework.data.domain.Sort.by("name").ascending()));
        List<ServiceSummary> rows = pageResult.getContent().stream()
                .map(ServiceTools::toSummary)
                .toList();
        return new ServicePage(p, s, pageResult.getTotalElements(), rows);
    }

    @Tool(description = "Get full details for one service: every services-row column plus joined APIs, "
            + "upstream/downstream service dependencies, databases used, and external dependencies. "
            + "Phase 3.5 will populate the relationship arrays that may be empty today.")
    public ServiceDetails getServiceDetails(
            @ToolParam(description = "Service ID (UUID).") String serviceId) {
        UUID id = parseUuid(serviceId);
        Service svc = services.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + id));

        return new ServiceDetails(
                svc.getId(),
                svc.getName(),
                svc.getDescription(),
                svc.getOwnerTeam(),
                svc.getStatus() == null ? null : svc.getStatus().name().toLowerCase(),
                svc.getLanguage(),
                svc.getFramework(),
                svc.getRepoUrl(),
                svc.getDeployment(),
                svc.getSupportContact(),
                svc.getSla(),
                svc.getNotes(),
                svc.getMetadata(),
                svc.getConfluencePageId(),
                svc.getLastSyncedToConfluence(),
                svc.getCreatedAt(),
                svc.getUpdatedAt(),
                relationships.findApisFor(id),
                relationships.findUpstreamDependenciesOf(id),
                relationships.findDownstreamDependenciesOf(id),
                relationships.findDatabasesFor(id),
                relationships.findExternalDependenciesFor(id));
    }

    private static ServiceSummary toSummary(Service s) {
        ServiceStatus status = s.getStatus();
        return new ServiceSummary(
                s.getId(),
                s.getName(),
                s.getOwnerTeam(),
                status == null ? null : status.name().toLowerCase());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("serviceId is required");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("serviceId is not a valid UUID: " + raw);
        }
    }
}
