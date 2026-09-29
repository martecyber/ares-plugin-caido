package com.martecyber.plugins.caido;

import com.martecyber.plugins.caido.dto.CaidoDtos;
import com.martecyber.plugins.caido.dto.CaidoDtos.CaidoApiTaskDto;
import com.martecyber.plugins.caido.dto.CaidoDtos.CreateCaidoApiTaskRequest;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaidoApiIntegrationActionHandlerTest {

    private CaidoApiIntegrationRepository integrationRepo;
    private CaidoApiTaskService taskService;
    private CaidoApiTaskRepository taskRepo;
    private CaidoApiIntegrationActionHandler handler;

    @BeforeEach
    void setUp() {
        integrationRepo = mock(CaidoApiIntegrationRepository.class);
        taskService = mock(CaidoApiTaskService.class);
        taskRepo = mock(CaidoApiTaskRepository.class);
        handler = new CaidoApiIntegrationActionHandler(integrationRepo, taskService, taskRepo);
    }

    @Test
    void describesBothKnownActions() {
        var codes = handler.describeActions().stream().map(a -> a.code()).toList();
        assertEquals(List.of(CaidoDtos.ACTION_PULL_SCOPE, CaidoDtos.ACTION_PUSH_FINDINGS), codes);
    }

    @Test
    void supportsOnlyProjectScope() {
        assertEquals(java.util.Set.of("project"), handler.supportedScopes());
    }

    @Test
    void listInstancesIgnoresScopeSinceConnectorsAreGlobal() {
        CaidoApiIntegration integration = mock(CaidoApiIntegration.class);
        when(integration.getId()).thenReturn(5L);
        when(integration.getLabel()).thenReturn("My Caido");
        when(integrationRepo.findAllByOrderByLabelAsc()).thenReturn(List.of(integration));

        List<IntegrationInstanceDescriptor> instances = handler.listInstances("project", 123L);

        assertEquals(1, instances.size());
        assertEquals(5L, instances.get(0).id());
        assertEquals("My Caido", instances.get(0).label());
    }

    @Test
    void startCreatesAOneShotTaskAndRunsItImmediately() {
        CaidoApiTaskDto created = new CaidoApiTaskDto(42L, 5L, "My Caido", 7L, "caido-proj-1", "Caido Project",
            CaidoDtos.ACTION_PULL_SCOPE, null, true, "pending", null, null, null, null);
        when(taskService.create(eq(7L), any(CreateCaidoApiTaskRequest.class))).thenReturn(created);

        Long refId = handler.start(CaidoDtos.ACTION_PULL_SCOPE, 5L, "project", 7L,
            Map.of("caidoProjectId", "caido-proj-1", "caidoProjectName", "Caido Project"));

        assertEquals(42L, refId);
        verify(taskService).create(eq(7L), argThat(req ->
            req.integrationId().equals(5L) && req.caidoProjectId().equals("caido-proj-1")
                && req.action().equals(CaidoDtos.ACTION_PULL_SCOPE) && req.cronExpression() == null));
        verify(taskService).runNow(7L, 42L);
    }

    @Test
    void startRequiresCaidoProjectIdParam() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> handler.start(CaidoDtos.ACTION_PULL_SCOPE, 5L, "project", 7L, Map.of()));
        assertTrue(ex.getMessage().contains("caidoProjectId"));
        verifyNoInteractions(taskService);
    }

    @Test
    void checkStatusMapsCompletedTaskToCompletedResult() {
        CaidoApiTask task = mock(CaidoApiTask.class);
        when(task.getStatus()).thenReturn("completed");
        when(task.getResult()).thenReturn("{\"findingsCreated\":3}");
        when(taskRepo.findById(42L)).thenReturn(Optional.of(task));

        IntegrationActionResult result = handler.checkStatus(42L);

        assertEquals(IntegrationActionResult.COMPLETED, result.state());
        assertEquals("{\"findingsCreated\":3}", result.outputJson());
        assertNull(result.error());
    }

    @Test
    void checkStatusMapsFailedTaskToFailedResult() {
        CaidoApiTask task = mock(CaidoApiTask.class);
        when(task.getStatus()).thenReturn("failed");
        when(task.getError()).thenReturn("Caido unreachable");
        when(taskRepo.findById(42L)).thenReturn(Optional.of(task));

        IntegrationActionResult result = handler.checkStatus(42L);

        assertEquals(IntegrationActionResult.FAILED, result.state());
        assertEquals("Caido unreachable", result.error());
    }

    @Test
    void checkStatusMapsPendingOrRunningTaskToRunningResult() {
        CaidoApiTask task = mock(CaidoApiTask.class);
        when(task.getStatus()).thenReturn("running");
        when(taskRepo.findById(42L)).thenReturn(Optional.of(task));

        IntegrationActionResult result = handler.checkStatus(42L);

        assertEquals(IntegrationActionResult.RUNNING, result.state());
    }

    @Test
    void checkStatusFailsCleanlyWhenTaskRowNoLongerExists() {
        when(taskRepo.findById(42L)).thenReturn(Optional.empty());

        IntegrationActionResult result = handler.checkStatus(42L);

        assertEquals(IntegrationActionResult.FAILED, result.state());
        assertTrue(result.error().contains("42"));
    }
}
