package com.martecyber.plugins.caido;

import com.martecyber.ares.plugins.PluginRestController;
import com.martecyber.plugins.caido.dto.CaidoDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Management API for Caido API integrations (push-based: Ares calls Caido's GraphQL directly).
 *
 * <p>Authorization here is checked programmatically ({@link #requireRole}) rather than via
 * {@code @PreAuthorize} — see {@code BugHuntingProgramController}'s own doc comment (same plugin
 * system, same reason: this class is plugin-loaded and implements no interface, and the proxy
 * factory backing {@code @PreAuthorize} is bound to the core app's own classloader, fixed once at
 * container startup, long before any plugin exists). {@code @ResponseStatus} needs no such proxy
 * (read directly off the method via reflection at dispatch time), so it's kept as-is.
 */
@RestController
@RequestMapping("/api/v1/caido-api")
public class CaidoApiController implements PluginRestController {

    private final CaidoApiIntegrationService integrationService;
    private final CaidoApiTaskService taskService;

    public CaidoApiController(CaidoApiIntegrationService integrationService,
                              CaidoApiTaskService taskService) {
        this.integrationService = integrationService;
        this.taskService = taskService;
    }

    // ── Integrations (admin) ───────────────────────────────────────────────────

    @GetMapping("/integrations")
    public List<CaidoApiIntegrationDto> list(Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return integrationService.list();
    }

    @GetMapping("/integrations/{id}")
    public CaidoApiIntegrationDto get(@PathVariable Long id, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return integrationService.get(id);
    }

    @PostMapping("/integrations")
    @ResponseStatus(HttpStatus.CREATED)
    public CaidoApiIntegrationDto create(@RequestBody CreateCaidoApiIntegrationRequest req, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        return integrationService.create(req);
    }

    @PatchMapping("/integrations/{id}")
    public CaidoApiIntegrationDto update(@PathVariable Long id, @RequestBody UpdateCaidoApiIntegrationRequest req, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        return integrationService.update(id, req);
    }

    @DeleteMapping("/integrations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        integrationService.delete(id);
    }

    @PostMapping("/integrations/{id}/test")
    public CaidoApiIntegrationDto testConnection(@PathVariable Long id, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return integrationService.testConnection(id);
    }

    @GetMapping("/integrations/{id}/caido-projects")
    public List<CaidoKnownProjectDto> caidoProjects(@PathVariable Long id, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return integrationService.knownProjects(id);
    }

    // ── Grants (admin) ─────────────────────────────────────────────────────────

    @GetMapping("/integrations/{id}/grants")
    public List<CaidoApiGrantDto> listGrants(@PathVariable Long id, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        return integrationService.listGrants(id);
    }

    @PostMapping("/integrations/{id}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public CaidoApiGrantDto createGrant(@PathVariable Long id, @RequestBody CreateCaidoApiGrantRequest req, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        return integrationService.createGrant(id, req);
    }

    @DeleteMapping("/integrations/{id}/grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGrant(@PathVariable Long id, @PathVariable Long grantId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        integrationService.deleteGrant(id, grantId);
    }

    // ── Per-project tasks (admin + operator) ───────────────────────────────────

    @GetMapping("/projects/{projectId}/integrations")
    public List<GrantedCaidoApiIntegrationDto> grantedIntegrations(@PathVariable Long projectId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        Long orgId = taskService.projectOrgId(projectId);
        return integrationService.grantedForProject(orgId, projectId);
    }

    @GetMapping("/projects/{projectId}/tasks")
    public List<CaidoApiTaskDto> listTasks(@PathVariable Long projectId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return taskService.listForProject(projectId);
    }

    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public CaidoApiTaskDto createTask(@PathVariable Long projectId, @RequestBody CreateCaidoApiTaskRequest req, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return taskService.create(projectId, req);
    }

    @PatchMapping("/projects/{projectId}/tasks/{taskId}")
    public CaidoApiTaskDto updateTask(@PathVariable Long projectId, @PathVariable Long taskId,
                                      @RequestBody UpdateCaidoApiTaskRequest req, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return taskService.update(projectId, taskId, req);
    }

    @DeleteMapping("/projects/{projectId}/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable Long projectId, @PathVariable Long taskId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        taskService.delete(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/run")
    public CaidoApiTaskDto runTask(@PathVariable Long projectId, @PathVariable Long taskId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return taskService.runNow(projectId, taskId);
    }

    private static void requireRole(Authentication auth, String... anyOfRoles) {
        boolean allowed = auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> {
                for (String role : anyOfRoles) {
                    if (("ROLE_" + role).equals(a.getAuthority())) return true;
                }
                return false;
            });
        if (!allowed) {
            throw new AccessDeniedException("Requires role: " + String.join(" or ", anyOfRoles));
        }
    }
}
