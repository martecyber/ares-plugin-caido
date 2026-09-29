package com.martecyber.plugins.caido;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PlatformFacade;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.plugins.caido.dto.CaidoDtos;
import com.martecyber.plugins.caido.dto.CaidoDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public class CaidoApiTaskService implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(CaidoApiTaskService.class);
    private static final Set<String> ACTIONS = Set.of(CaidoDtos.ACTION_PULL_SCOPE, CaidoDtos.ACTION_PUSH_FINDINGS);

    private final CaidoApiTaskRepository repo;
    private final CaidoApiIntegrationRepository integrationRepo;
    private final ProjectFacade projectFacade;
    private final PlatformFacade platformFacade;

    public CaidoApiTaskService(CaidoApiTaskRepository repo,
                               CaidoApiIntegrationRepository integrationRepo,
                               ProjectFacade projectFacade,
                               PlatformFacade platformFacade) {
        this.repo = repo;
        this.integrationRepo = integrationRepo;
        this.projectFacade = projectFacade;
        this.platformFacade = platformFacade;
    }

    public List<CaidoApiTaskDto> listForProject(Long projectId) {
        return repo.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
            .map(t -> CaidoApiTaskDto.from(t, integrationLabel(t.getIntegrationId())))
            .toList();
    }

    public CaidoApiTaskDto create(Long projectId, CreateCaidoApiTaskRequest req) {
        if (!projectFacade.exists(projectId)) throw NotFoundException.of("project", projectId);
        if (req.integrationId() == null || !integrationRepo.existsById(req.integrationId()))
            throw NotFoundException.of("caido_api_integration", req.integrationId());
        if (req.caidoProjectId() == null || req.caidoProjectId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caidoProjectId is required");
        if (req.action() == null || !ACTIONS.contains(req.action()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action: " + req.action());

        var t = new CaidoApiTask();
        t.setIntegrationId(req.integrationId());
        t.setProjectId(projectId);
        t.setCaidoProjectId(req.caidoProjectId().trim());
        t.setCaidoProjectName(req.caidoProjectName());
        t.setAction(req.action());
        if (req.enabled() != null) t.setEnabled(req.enabled());
        if (req.excludedScopeValues() != null && !req.excludedScopeValues().isEmpty())
            t.setExcludedScopeValues(req.excludedScopeValues());
        String cron = normalizeCron(req.cronExpression());
        if (cron != null) {
            t.setCronExpression(cron);
            t.setNextRunAt(computeNext(cron));
        }
        return CaidoApiTaskDto.from(repo.save(t), integrationLabel(t.getIntegrationId()));
    }

    public CaidoApiTaskDto update(Long projectId, Long taskId, UpdateCaidoApiTaskRequest req) {
        var t = findInProject(projectId, taskId);
        if (req.caidoProjectName() != null) t.setCaidoProjectName(req.caidoProjectName());
        if (req.enabled() != null) t.setEnabled(req.enabled());
        if (req.cronExpression() != null) {
            String cron = normalizeCron(req.cronExpression());
            t.setCronExpression(cron);
            t.setNextRunAt(cron != null ? computeNext(cron) : null);
        }
        return CaidoApiTaskDto.from(repo.save(t), integrationLabel(t.getIntegrationId()));
    }

    public void delete(Long projectId, Long taskId) {
        repo.delete(findInProject(projectId, taskId));
    }

    public CaidoApiTaskDto runNow(Long projectId, Long taskId) {
        var t = findInProject(projectId, taskId);
        t.setStatus("pending");
        t.setNextRunAt(OffsetDateTime.now());
        return CaidoApiTaskDto.from(repo.save(t), integrationLabel(t.getIntegrationId()));
    }

    public void markRunning(CaidoApiTask t) {
        t.setStatus("running");
        repo.save(t);
    }

    public void markDone(Long taskId, boolean success, String error, String resultJson) {
        var t = repo.findById(taskId).orElse(null);
        if (t == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        t.setLastRunAt(now);
        t.setError(success ? null : error);
        t.setResult(resultJson);
        t.setStatus(success ? "completed" : "failed");
        if (t.getCronExpression() != null) {
            t.setNextRunAt(computeNext(t.getCronExpression()));
        }
        repo.save(t);
    }

    CaidoApiTask findInProject(Long projectId, Long taskId) {
        return repo.findById(taskId)
            .filter(t -> t.getProjectId().equals(projectId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caido API task not found: " + taskId));
    }

    Long projectOrgId(Long projectId) {
        return projectFacade.getOrganizationId(projectId);
    }

    private String integrationLabel(Long integrationId) {
        return integrationRepo.findById(integrationId).map(CaidoApiIntegration::getLabel).orElse(null);
    }

    static String normalizeCron(String cron) {
        if (cron == null || cron.isBlank()) return null;
        String trimmed = cron.trim();
        return (trimmed.split("\\s+").length == 5) ? "0 " + trimmed : trimmed;
    }

    /** See {@code AgentTaskScheduleService.computeNextRun}'s doc comment — the cron's wall-clock
     *  fields are meant in the platform-configured timezone, not the JVM's own default zone. */
    OffsetDateTime computeNext(String springCron) {
        try {
            ZoneId zone = ZoneId.of(platformFacade.getTimezone());
            LocalDateTime nowLocal = OffsetDateTime.now().atZoneSameInstant(zone).toLocalDateTime();
            LocalDateTime next = CronExpression.parse(springCron).next(nowLocal);
            return next != null ? next.atZone(zone).toOffsetDateTime() : null;
        } catch (Exception e) {
            log.warn("Invalid cron '{}': {}", springCron, e.getMessage());
            return null;
        }
    }
}
