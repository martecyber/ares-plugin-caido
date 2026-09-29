package com.martecyber.plugins.caido.dto;

import com.martecyber.plugins.caido.CaidoApiIntegration;
import com.martecyber.plugins.caido.CaidoApiIntegrationGrant;
import com.martecyber.plugins.caido.CaidoApiTask;

import java.time.OffsetDateTime;
import java.util.List;

public class CaidoDtos {

    /** Actions a Caido API task can perform. */
    public static final String ACTION_PULL_SCOPE   = "PULL_SCOPE";
    public static final String ACTION_PUSH_FINDINGS = "PUSH_FINDINGS";

    // ── Caido API integration (push-based, Ares → Caido GraphQL) ─────────────────

    public record CaidoApiIntegrationDto(
        Long id,
        String label,
        String baseUrl,
        boolean enabled,
        String connectionStatus,
        String connectionError,
        OffsetDateTime lastTestedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        public static CaidoApiIntegrationDto from(CaidoApiIntegration i) {
            return new CaidoApiIntegrationDto(
                i.getId(), i.getLabel(), i.getBaseUrl(), i.isEnabled(),
                i.getConnectionStatus(), i.getConnectionError(), i.getLastTestedAt(),
                i.getCreatedAt(), i.getUpdatedAt()
            );
        }
    }

    public record CreateCaidoApiIntegrationRequest(String label, String baseUrl, String accessToken, Boolean enabled) {}
    public record UpdateCaidoApiIntegrationRequest(String label, String baseUrl, String accessToken, Boolean enabled) {}

    public record CaidoApiGrantDto(
        Long id,
        Long integrationId,
        Long organizationId,
        Long projectId,
        List<String> capabilities,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        public static CaidoApiGrantDto from(CaidoApiIntegrationGrant g) {
            return new CaidoApiGrantDto(
                g.getId(), g.getIntegrationId(), g.getOrganizationId(),
                g.getProjectId(), g.getCapabilities(), g.isActive(),
                g.getCreatedAt(), g.getUpdatedAt()
            );
        }
    }

    public record CreateCaidoApiGrantRequest(
        Long organizationId,
        Long projectId,
        List<String> capabilities
    ) {}

    public record CaidoApiTaskDto(
        Long id,
        Long integrationId,
        String integrationLabel,
        Long projectId,
        String caidoProjectId,
        String caidoProjectName,
        String action,
        String cronExpression,
        boolean enabled,
        String status,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime lastRunAt,
        OffsetDateTime nextRunAt
    ) {
        public static CaidoApiTaskDto from(CaidoApiTask t, String integrationLabel) {
            return new CaidoApiTaskDto(
                t.getId(), t.getIntegrationId(), integrationLabel, t.getProjectId(),
                t.getCaidoProjectId(), t.getCaidoProjectName(), t.getAction(),
                t.getCronExpression(), t.isEnabled(), t.getStatus(), t.getError(),
                t.getCreatedAt(), t.getLastRunAt(), t.getNextRunAt()
            );
        }
    }

    public record CreateCaidoApiTaskRequest(
        Long integrationId,
        String caidoProjectId,
        String caidoProjectName,
        String action,
        String cronExpression,
        Boolean enabled,
        List<String> excludedScopeValues
    ) {}

    public record UpdateCaidoApiTaskRequest(
        String caidoProjectName,
        String cronExpression,
        Boolean enabled
    ) {}

    public record GrantedCaidoApiIntegrationDto(
        Long integrationId,
        String label,
        boolean enabled,
        String connectionStatus,
        OffsetDateTime lastTestedAt,
        List<String> capabilities
    ) {}

    /** A Caido project available on the linked instance/API. */
    public record CaidoKnownProjectDto(String id, String name) {}
}
