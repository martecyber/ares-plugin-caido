package com.martecyber.plugins.caido;

import java.time.OffsetDateTime;
import java.util.List;

/** Grants a project (or whole org) access to a {@link CaidoApiIntegration}.
 *
 * <p>Plain POJO, not a JPA {@code @Entity} — see {@link CaidoApiIntegration}'s own doc for why. */
public class CaidoApiIntegrationGrant {

    private Long id;
    private Long integrationId;
    private Long organizationId;

    /** Null = org-wide grant; non-null = restricted to one project. */
    private Long projectId;

    private List<String> capabilities = List.of();
    private boolean active = true;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
