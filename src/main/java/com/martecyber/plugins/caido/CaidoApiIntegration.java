package com.martecyber.plugins.caido;

import java.time.OffsetDateTime;

/**
 * A Caido connection Ares drives directly via Caido's GraphQL API (push-based). Requires Caido to
 * be network-reachable from the Ares server. The access token is stored AES-256-GCM encrypted via
 * {@link com.martecyber.ares.integrations.CredentialCryptoFacade}, same as Shodan.
 *
 * <p>Plain POJO, not a JPA {@code @Entity} — Hibernate maps its entities once at boot, before any
 * plugin loads, so a plugin-owned table can't go through it. Persisted via {@link
 * CaidoApiIntegrationRepository} (hand-written {@code JdbcTemplate} SQL) against a table this
 * plugin creates itself in {@link CaidoApiPluginLifecycle#onInstall}.
 */
public class CaidoApiIntegration {

    private Long id;
    private String label;
    private String baseUrl;
    private byte[] accessTokenCiphertext;
    private byte[] accessTokenIv;
    private boolean enabled = true;

    /** unknown | ok | error */
    private String connectionStatus = "unknown";

    private String connectionError;
    private OffsetDateTime lastTestedAt;

    /** Caido projects available on this instance (JSON array); populated by testConnection. */
    private String knownProjects = "[]";

    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public byte[] getAccessTokenCiphertext() { return accessTokenCiphertext; }
    public void setAccessTokenCiphertext(byte[] v) { this.accessTokenCiphertext = v; }

    public byte[] getAccessTokenIv() { return accessTokenIv; }
    public void setAccessTokenIv(byte[] v) { this.accessTokenIv = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }

    public String getConnectionError() { return connectionError; }
    public void setConnectionError(String connectionError) { this.connectionError = connectionError; }

    public OffsetDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(OffsetDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public String getKnownProjects() { return knownProjects; }
    public void setKnownProjects(String knownProjects) { this.knownProjects = knownProjects; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
