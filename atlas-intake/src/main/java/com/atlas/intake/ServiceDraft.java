package com.atlas.intake;

import com.atlas.services.ServiceStatus;

public record ServiceDraft(String name, String description, String ownerTeam, ServiceStatus status) {

    public static ServiceDraft empty() {
        return new ServiceDraft(null, null, null, null);
    }

    public ServiceDraft withName(String n) { return new ServiceDraft(n, description, ownerTeam, status); }
    public ServiceDraft withDescription(String d) { return new ServiceDraft(name, d, ownerTeam, status); }
    public ServiceDraft withOwnerTeam(String o) { return new ServiceDraft(name, description, o, status); }
    public ServiceDraft withStatus(ServiceStatus s) { return new ServiceDraft(name, description, ownerTeam, s); }
}
