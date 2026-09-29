package com.martecyber.plugins.caido;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A unit of work Ares executes against Caido via its GraphQL API.
 *
 * <p>Plain POJO, not a JPA {@code @Entity} — see {@link CaidoApiIntegration}'s own doc for why.
 * Persisted via {@link CaidoApiTaskRepository} against a table this plugin creates itself in
 * {@link CaidoApiPluginLifecycle#onInstall}.
 */
public class CaidoApiTask {

    private Long id;
    private Long integrationId;
    private Long projectId;
    private String caidoProjectId;
    private String caidoProjectName;

    /** PULL_SCOPE | PUSH_FINDINGS */
    private String action;

    /** Null = one-shot; non-null = recurring (standard 5-field cron). */
    private String cronExpression;

    private boolean enabled = true;

    /** pending | running | completed | failed */
    private String status = "pending";

    private String result;
    private String error;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime lastRunAt;
    private OffsetDateTime nextRunAt;
    private List<String> excludedScopeValues;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getCaidoProjectId() { return caidoProjectId; }
    public void setCaidoProjectId(String caidoProjectId) { this.caidoProjectId = caidoProjectId; }

    public String getCaidoProjectName() { return caidoProjectName; }
    public void setCaidoProjectName(String caidoProjectName) { this.caidoProjectName = caidoProjectName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public List<String> getExcludedScopeValues() { return excludedScopeValues; }
    public void setExcludedScopeValues(List<String> excludedScopeValues) { this.excludedScopeValues = excludedScopeValues; }
}
