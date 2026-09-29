package com.martecyber.plugins.caido;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.assets.AssetFacade;
import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.detections.DetectionFacade;
import com.martecyber.ares.imports.ParsedAsset;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.ares.projects.ScopeFacade;
import com.martecyber.ares.projects.dto.ScopeEntryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Executes a single {@link CaidoApiTask} by calling Caido's GraphQL API directly:
 * <ol>
 *   <li>Remember the current active project.</li>
 *   <li>selectProject(task.caidoProjectId)</li>
 *   <li>Execute the action (PULL_SCOPE or PUSH_FINDINGS).</li>
 *   <li>Restore the original project.</li>
 * </ol>
 */
public class CaidoApiTaskExecutor implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(CaidoApiTaskExecutor.class);
    private static final String SOURCE_TYPE = "caido";

    private final CaidoGraphQLClient graphql;
    private final CaidoApiIntegrationService integrationService;
    private final CaidoApiTaskService taskService;
    private final ProjectFacade projectFacade;
    private final ScopeFacade scopeFacade;
    private final DetectionFacade detectionFacade;
    private final AssetFacade assetFacade;
    private final ObjectMapper objectMapper;

    public CaidoApiTaskExecutor(
        CaidoGraphQLClient graphql,
        CaidoApiIntegrationService integrationService,
        CaidoApiTaskService taskService,
        ProjectFacade projectFacade,
        ScopeFacade scopeFacade,
        DetectionFacade detectionFacade,
        AssetFacade assetFacade,
        ObjectMapper objectMapper
    ) {
        this.graphql = graphql;
        this.integrationService = integrationService;
        this.taskService = taskService;
        this.projectFacade = projectFacade;
        this.scopeFacade = scopeFacade;
        this.detectionFacade = detectionFacade;
        this.assetFacade = assetFacade;
        this.objectMapper = objectMapper;
    }

    public void execute(CaidoApiTask task) {
        var integration = integrationService.find(task.getIntegrationId());
        String token = integrationService.decryptToken(integration);
        String baseUrl = integration.getBaseUrl();

        taskService.markRunning(task);

        // Remember current project to restore after execution.
        Map<String, String> originalProject = null;
        try {
            originalProject = graphql.getCurrentProject(baseUrl, token);
        } catch (Exception e) {
            log.warn("Could not get current Caido project before selectProject: {}", e.getMessage());
        }

        boolean success = false;
        String error = null;
        String resultJson = null;

        try {
            graphql.selectProject(baseUrl, token, task.getCaidoProjectId());

            if ("PULL_SCOPE".equals(task.getAction())) {
                resultJson = executePullScope(task, baseUrl, token);
            } else if ("PUSH_FINDINGS".equals(task.getAction())) {
                resultJson = executePushFindings(task, baseUrl, token);
            }
            success = true;
        } catch (Exception e) {
            error = e.getMessage();
            log.error("Caido API task {} failed: {}", task.getId(), e.getMessage(), e);
        } finally {
            // Best-effort: restore original project.
            if (originalProject != null) {
                try {
                    graphql.selectProject(baseUrl, token, originalProject.get("id"));
                } catch (Exception e) {
                    log.warn("Failed to restore original Caido project '{}': {}", originalProject.get("id"), e.getMessage());
                }
            }
        }

        taskService.markDone(task.getId(), success, error, resultJson);
    }

    // ── PULL_SCOPE ────────────────────────────────────────────────────────────────

    private String executePullScope(CaidoApiTask task, String baseUrl, String token) throws Exception {
        var entries = scopeFacade.listAll(task.getProjectId());
        Set<String> excluded = task.getExcludedScopeValues() != null
            ? new HashSet<>(task.getExcludedScopeValues()) : Collections.emptySet();
        var allowlist = new ArrayList<String>();
        var denylist  = new ArrayList<String>();
        for (ScopeEntryDto e : entries) {
            String pattern = toCaidoPattern(e.kind(), e.value());
            if (pattern == null) continue;
            if (!excluded.isEmpty() && excluded.contains(e.value())) continue;
            if (e.inScope()) allowlist.add(pattern); else denylist.add(pattern);
        }

        String scopeName = "Ares — " + task.getProjectId();
        var scopes = graphql.listScopes(baseUrl, token);
        var existing = scopes.stream().filter(s -> scopeName.equals(s.name())).findFirst();

        if (existing.isPresent()) {
            graphql.updateScope(baseUrl, token, existing.get().id(), scopeName, allowlist, denylist);
        } else {
            graphql.createScope(baseUrl, token, scopeName, allowlist, denylist);
        }

        var result = Map.of("allowlistEntries", allowlist.size(), "denylistEntries", denylist.size());
        return objectMapper.writeValueAsString(result);
    }

    // ── PUSH_FINDINGS ─────────────────────────────────────────────────────────────

    private String executePushFindings(CaidoApiTask task, String baseUrl, String token) throws Exception {
        Long projectId = task.getProjectId();
        Long orgId = projectFacade.getOrganizationId(projectId);

        int created = 0, updated = 0;
        int offset = 0;
        final int limit = 200;

        for (;;) {
            var page = graphql.getFindingsByOffset(baseUrl, token, offset, limit);
            for (var finding : page.nodes()) {
                String url = buildUrl(finding);
                Long assetId = resolveEndpointAsset(orgId, projectId, url);
                var r = detectionFacade.ingestExternal(
                    projectId, SOURCE_TYPE, finding.id(), assetId,
                    "info", finding.title().isBlank() ? "Caido finding" : finding.title(),
                    finding.description(), null);
                if (r.created()) created++; else updated++;
                // Unconditional on create-vs-update, same as CaidoIngestService's plugin-driven
                // path — every push re-attaches whatever request/response Caido has now, so a
                // re-run after the target responds fills in a sample that was missing before.
                if (notBlank(finding.requestRaw()) || notBlank(finding.responseRaw())) {
                    detectionFacade.attachHttpSample(r.detectionId(), "Caido", finding.requestRaw(), finding.responseRaw(), null);
                }
            }
            if (!page.hasMore()) break;
            offset += limit;
        }

        var result = Map.of("findingsCreated", created, "findingsUpdated", updated);
        return objectMapper.writeValueAsString(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Long resolveEndpointAsset(Long orgId, Long projectId, String url) {
        String endpointIdentifier = stripQuery(url);
        if (endpointIdentifier == null) return null;

        String webAppIdentifier = baseUrl(url);
        Long webAppId = null;
        if (webAppIdentifier != null) {
            webAppId = assetFacade.resolveOrCreate(orgId,
                new ParsedAsset(webAppIdentifier, AssetType.WEB_APPLICATION, Map.of("source", "caido")));
            if (webAppId != null) {
                assetFacade.grantProjectAccess(projectId, webAppId);
                assetFacade.ensureWebApplicationTree(orgId, projectId, webAppId, webAppIdentifier);
                assetFacade.recordToolSighting(webAppId, projectId, SOURCE_TYPE);
            }
        }
        Long endpointId = assetFacade.resolveOrCreate(orgId,
            new ParsedAsset(endpointIdentifier, AssetType.WEB_ENDPOINT, Map.of("source", "caido")));
        if (endpointId != null) {
            assetFacade.grantProjectAccess(projectId, endpointId);
            if (webAppId != null) assetFacade.linkIfAbsent(webAppId, endpointId, AssetLinkType.WEBAPP_ENDPOINT);
            assetFacade.recordToolSighting(endpointId, projectId, SOURCE_TYPE);
        }
        return endpointId;
    }

    private static String buildUrl(CaidoGraphQLClient.CaidoFindingNode n) {
        String scheme = n.isTls() ? "https" : "http";
        int defaultPort = n.isTls() ? 443 : 80;
        String portPart = (n.port() > 0 && n.port() != defaultPort) ? ":" + n.port() : "";
        String queryPart = (n.query() != null && !n.query().isBlank()) ? "?" + n.query() : "";
        return scheme + "://" + n.host() + portPart + n.path() + queryPart;
    }

    private static String baseUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return uri.getPort() == -1
                ? uri.getScheme() + "://" + uri.getHost()
                : uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
        } catch (Exception e) { return null; }
    }

    private static String stripQuery(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            String base = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            String path = uri.getRawPath() != null ? uri.getRawPath() : "";
            return path.isEmpty() || path.equals("/") ? base : base + path;
        } catch (Exception e) { return null; }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String toCaidoPattern(String kind, String value) {
        if (value == null || value.isBlank()) return null;
        return switch (kind) {
            case "domain_wildcard" -> value.startsWith("*.") ? value : "*." + value.replaceAll("^\\*?\\.?", "");
            case "ip", "domain", "url" -> value;
            default -> null; // cidr and other kinds not supported by Caido scope
        };
    }
}
