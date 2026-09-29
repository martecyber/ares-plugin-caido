package com.martecyber.plugins.caido;

import com.martecyber.plugins.caido.dto.CaidoDtos;
import com.martecyber.plugins.caido.dto.CaidoDtos.CreateCaidoApiTaskRequest;
import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires Caido's existing PULL_SCOPE/PUSH_FINDINGS task model into {@code ACTION_INTEGRATION_CALL}
 * — reuses {@link CaidoApiTaskService}/{@link CaidoApiTaskScheduler}/{@link CaidoApiTaskExecutor}
 * unmodified, all now living in this same plugin (entities/repositories/controller/GraphQL client
 * included — the whole "Caido (API)" subsystem was moved out of ares-core in one pass, via
 * hand-written {@code JdbcTemplate} DAOs over its already-existing tables, a {@link
 * CaidoApiController} implementing {@code PluginRestController}, and the {@code ares-sdk}
 * facades — {@code AssetFacade}/{@code DetectionFacade}/{@code CredentialCryptoFacade}/{@code
 * PlatformFacade} — the executor needed). A workflow-triggered call is just a one-shot ({@code
 * cronExpression=null}) {@link CaidoApiTask}, so it shows up in the project's own Caido task list
 * like any manually-created one (a natural audit trail of what a workflow did), runs on the same
 * 60s scheduler, and needs zero new execution code — only enough glue to create+runNow it and read
 * its terminal status back for {@code WorkflowStepPoller}. Preserves the exact {@code
 * integrationType()} ("caido-api") and action codes core's own version used, so already-saved
 * Workflow graphs keep validating.
 */
public class CaidoApiIntegrationActionHandler implements IntegrationActionHandler {

    private final CaidoApiIntegrationRepository integrationRepo;
    private final CaidoApiTaskService taskService;
    private final CaidoApiTaskRepository taskRepo;

    public CaidoApiIntegrationActionHandler(CaidoApiIntegrationRepository integrationRepo,
                                             CaidoApiTaskService taskService,
                                             CaidoApiTaskRepository taskRepo) {
        this.integrationRepo = integrationRepo;
        this.taskService = taskService;
        this.taskRepo = taskRepo;
    }

    @Override
    public String integrationType() { return "caido-api"; }

    @Override
    public String integrationTypeLabel() { return "Caido (API)"; }

    @Override
    public Set<String> supportedScopes() { return Set.of("project"); }

    @Override
    public List<IntegrationActionDescriptor> describeActions() {
        return List.of(
            new IntegrationActionDescriptor(CaidoDtos.ACTION_PULL_SCOPE, "Pull scope from Caido"),
            new IntegrationActionDescriptor(CaidoDtos.ACTION_PUSH_FINDINGS, "Push findings to Caido"));
    }

    @Override
    public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) {
        // CaidoApiIntegration has no org/project column — connectors are configured
        // platform-wide and usable from any project, so scopeKind/scopeId aren't filters here.
        return integrationRepo.findAllByOrderByLabelAsc().stream()
            .map(i -> new IntegrationInstanceDescriptor(i.getId(), i.getLabel()))
            .toList();
    }

    @Override
    public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) {
        String caidoProjectId = stringParam(params, "caidoProjectId");
        if (caidoProjectId == null || caidoProjectId.isBlank()) {
            throw new IllegalArgumentException("Caido action requires a 'caidoProjectId' parameter");
        }
        String caidoProjectName = stringParam(params, "caidoProjectName");
        var req = new CreateCaidoApiTaskRequest(integrationInstanceId, caidoProjectId, caidoProjectName,
            actionCode, null, true, listParam(params, "excludedScopeValues"));
        Long projectId = scopeId; // supportedScopes() is project-only, so scopeId is always a project id here.
        var created = taskService.create(projectId, req);
        taskService.runNow(projectId, created.id());
        return created.id();
    }

    @Override
    public IntegrationActionResult checkStatus(Long refId) {
        CaidoApiTask task = taskRepo.findById(refId).orElse(null);
        if (task == null) {
            return new IntegrationActionResult(IntegrationActionResult.FAILED, null, "Caido API task " + refId + " no longer exists");
        }
        return switch (task.getStatus()) {
            case "completed" -> new IntegrationActionResult(IntegrationActionResult.COMPLETED, task.getResult(), null);
            case "failed" -> new IntegrationActionResult(IntegrationActionResult.FAILED, null,
                task.getError() != null ? task.getError() : "Caido API task failed");
            default -> new IntegrationActionResult(IntegrationActionResult.RUNNING, null, null);
        };
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }
}
