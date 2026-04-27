package com.atlas.intake;

import com.atlas.services.ServiceStatus;

public record ServiceDraft(
        String name,
        String description,
        String ownerTeam,
        ServiceStatus status,
        String language,
        String framework,
        String repoUrl,
        String deployment,
        String supportContact,
        String sla,
        String notes) {

    public static ServiceDraft empty() {
        return new ServiceDraft(null, null, null, null, null, null, null, null, null, null, null);
    }

    public ServiceDraft withName(String v) {
        return new ServiceDraft(v, description, ownerTeam, status, language, framework, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withDescription(String v) {
        return new ServiceDraft(name, v, ownerTeam, status, language, framework, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withOwnerTeam(String v) {
        return new ServiceDraft(name, description, v, status, language, framework, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withStatus(ServiceStatus v) {
        return new ServiceDraft(name, description, ownerTeam, v, language, framework, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withLanguage(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, v, framework, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withFramework(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, v, repoUrl, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withRepoUrl(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, framework, v, deployment, supportContact, sla, notes);
    }
    public ServiceDraft withDeployment(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, framework, repoUrl, v, supportContact, sla, notes);
    }
    public ServiceDraft withSupportContact(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, framework, repoUrl, deployment, v, sla, notes);
    }
    public ServiceDraft withSla(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, framework, repoUrl, deployment, supportContact, v, notes);
    }
    public ServiceDraft withNotes(String v) {
        return new ServiceDraft(name, description, ownerTeam, status, language, framework, repoUrl, deployment, supportContact, sla, v);
    }
}
