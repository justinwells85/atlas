package com.atlas.services;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "services")
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "owner_team")
    private String ownerTeam;

    @Column(nullable = false)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    private String language;

    private String framework;

    @Column(name = "repo_url")
    private String repoUrl;

    private String deployment;

    @Column(name = "support_contact")
    private String supportContact;

    private String sla;

    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "confluence_page_id")
    private String confluencePageId;

    @Column(name = "last_synced_to_confluence")
    private OffsetDateTime lastSyncedToConfluence;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * ADR-008: bump updated_at on every JPA-managed update. Raw SQL writes
     * bypass this — acceptable since the application is the only sanctioned writer.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwnerTeam() { return ownerTeam; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }

    public ServiceStatus getStatus() { return status; }
    public void setStatus(ServiceStatus status) { this.status = status; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getDeployment() { return deployment; }
    public void setDeployment(String deployment) { this.deployment = deployment; }

    public String getSupportContact() { return supportContact; }
    public void setSupportContact(String supportContact) { this.supportContact = supportContact; }

    public String getSla() { return sla; }
    public void setSla(String sla) { this.sla = sla; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String getConfluencePageId() { return confluencePageId; }
    public void setConfluencePageId(String confluencePageId) { this.confluencePageId = confluencePageId; }

    public OffsetDateTime getLastSyncedToConfluence() { return lastSyncedToConfluence; }
    public void setLastSyncedToConfluence(OffsetDateTime t) { this.lastSyncedToConfluence = t; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
