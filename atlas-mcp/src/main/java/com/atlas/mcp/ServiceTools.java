package com.atlas.mcp;

import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
        return toDetails(svc);
    }

    @Tool(description = "Update a service. All fields except serviceId are optional; passing null or omitting a field "
            + "leaves it unchanged. Writes only to columns on the services row — relationship-table writes (apis, "
            + "service dependencies, databases, external deps) are out of scope here and arrive in Phase 3.5. "
            + "When at least one field changes, updated_at is bumped per ADR-008.")
    public ServiceDetails updateService(
            @ToolParam(description = "Service ID (UUID).")
            String serviceId,
            @ToolParam(required = false, description = "New service name. Must be unique across all services.")
            String name,
            @ToolParam(required = false, description = "New description.")
            String description,
            @ToolParam(required = false, description = "New owner team.")
            String ownerTeam,
            @ToolParam(required = false, description = "New status. One of: active, deprecated, in_dev.")
            String status,
            @ToolParam(required = false, description = "Implementation language.")
            String language,
            @ToolParam(required = false, description = "Framework or runtime.")
            String framework,
            @ToolParam(required = false, description = "Source repository URL.")
            String repoUrl,
            @ToolParam(required = false, description = "Deployment target description.")
            String deployment,
            @ToolParam(required = false, description = "Support contact (email, oncall channel, etc.).")
            String supportContact,
            @ToolParam(required = false, description = "SLA description.")
            String sla,
            @ToolParam(required = false, description = "Free-form notes.")
            String notes,
            @ToolParam(required = false, description = "Replaces the metadata JSON object entirely. Pass null to leave it unchanged.")
            Map<String, Object> metadata,
            @ToolParam(required = false, description = "Confluence page ID this service syncs to.")
            String confluencePageId,
            @ToolParam(required = false, description = "ISO-8601 timestamp the service was last synced to Confluence.")
            OffsetDateTime lastSyncedToConfluence) {

        UUID id = parseUuid(serviceId);
        Service svc = services.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + id));

        boolean changed = false;
        if (name != null) { svc.setName(name); changed = true; }
        if (description != null) { svc.setDescription(description); changed = true; }
        if (ownerTeam != null) { svc.setOwnerTeam(ownerTeam); changed = true; }
        if (status != null) { svc.setStatus(parseStatus(status)); changed = true; }
        if (language != null) { svc.setLanguage(language); changed = true; }
        if (framework != null) { svc.setFramework(framework); changed = true; }
        if (repoUrl != null) { svc.setRepoUrl(repoUrl); changed = true; }
        if (deployment != null) { svc.setDeployment(deployment); changed = true; }
        if (supportContact != null) { svc.setSupportContact(supportContact); changed = true; }
        if (sla != null) { svc.setSla(sla); changed = true; }
        if (notes != null) { svc.setNotes(notes); changed = true; }
        if (metadata != null) { svc.setMetadata(metadata); changed = true; }
        if (confluencePageId != null) { svc.setConfluencePageId(confluencePageId); changed = true; }
        if (lastSyncedToConfluence != null) { svc.setLastSyncedToConfluence(lastSyncedToConfluence); changed = true; }

        if (changed) {
            try {
                svc = services.saveAndFlush(svc);
            } catch (DataIntegrityViolationException e) {
                // Surface unique-name violations (and any other constraint hits)
                // inside the tool method so Spring AI returns an MCP error result
                // instead of failing later at transaction commit.
                throw new IllegalArgumentException("Update violated a database constraint: " + e.getMostSpecificCause().getMessage(), e);
            }
        }
        return toDetails(svc);
    }

    private ServiceDetails toDetails(Service svc) {
        UUID id = svc.getId();
        return new ServiceDetails(
                id,
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

    private static ServiceStatus parseStatus(String raw) {
        String normalised = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return switch (normalised) {
            case "active" -> ServiceStatus.ACTIVE;
            case "deprecated" -> ServiceStatus.DEPRECATED;
            case "in_dev", "indev" -> ServiceStatus.IN_DEV;
            default -> throw new IllegalArgumentException(
                    "status must be one of: active, deprecated, in_dev (got: " + raw + ")");
        };
    }
}
