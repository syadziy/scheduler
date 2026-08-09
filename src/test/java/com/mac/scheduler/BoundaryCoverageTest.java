package com.mac.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mac.scheduler.config.properties.ThresholdAlertProperties;
import com.mac.scheduler.controller.SchedulerManagementController;
import com.mac.scheduler.entities.constant.*;
import com.mac.scheduler.entities.dto.*;
import com.mac.scheduler.entities.model.*;
import com.mac.scheduler.job.SchedulerPollingJob;
import com.mac.scheduler.repository.ScheduleRepository;
import com.mac.scheduler.repository.TaskHistoryRepository;
import com.mac.scheduler.service.*;
import com.mac.scheduler.service.impl.*;
import com.mac.scheduler.utils.WorkerIdentity;
import com.mac.scheduler.utils.exception.SchedulerConflictException;
import com.mac.scheduler.utils.handler.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class BoundaryCoverageTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void managementControllerDelegatesAllCreateOperations() {
        TaskService taskService = mock(TaskService.class);
        TaskGroupService groupService = mock(TaskGroupService.class);
        ScheduleService scheduleService = mock(ScheduleService.class);
        SchedulerManagementController controller =
                new SchedulerManagementController(taskService, groupService, scheduleService);

        UUID taskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        CreateTaskRequest taskRequest = new CreateTaskRequest("task", HttpMethod.GET,
                URI.create("https://example.com"), Map.of(), null, null, Duration.ofSeconds(1), true);
        CreateTaskGroupRequest groupRequest = new CreateTaskGroupRequest(
                "group", GroupExecutionMode.SERIAL, List.of(taskId), List.of(), true);
        CreateScheduleRequest scheduleRequest = new CreateScheduleRequest(
                "schedule", ScheduleTargetType.TASK, taskId, null, "0 * * * * *", "UTC", true);
        when(taskService.create(taskRequest)).thenReturn(new CreateTaskResponse(taskId, "task", NOW));
        when(groupService.create(groupRequest)).thenReturn(new CreateTaskGroupResponse(
                groupId, "group", GroupExecutionMode.SERIAL, List.of(taskId), List.of(), NOW));
        when(scheduleService.create(scheduleRequest)).thenReturn(new CreateScheduleResponse(
                scheduleId, "schedule", ScheduleTargetType.TASK, taskId, NOW, NOW));

        var taskResponse = controller.createTask(taskRequest);
        var groupResponse = controller.createTaskGroup(groupRequest);
        var scheduleResponse = controller.createSchedule(scheduleRequest);
        assertEquals(HttpStatus.CREATED, taskResponse.getStatusCode());
        assertEquals("/api/v1/tasks/" + taskId, taskResponse.getHeaders().getLocation().toString());
        assertEquals("/api/v1/task-groups/" + groupId, groupResponse.getHeaders().getLocation().toString());
        assertEquals("/api/v1/schedules/" + scheduleId, scheduleResponse.getHeaders().getLocation().toString());
    }

    @Test
    void historyServiceMapsAndCounts() {
        TaskHistoryRepository repository = mock(TaskHistoryRepository.class);
        HistoryServiceImpl service = new HistoryServiceImpl(repository);
        HistoryFilter filter = new HistoryFilter(NOW.minusSeconds(1), NOW, null, null, null, 10, 0);
        TaskExecutionResult result = result();
        when(repository.find(filter)).thenReturn(List.of(result));
        when(repository.count(filter)).thenReturn(9L);
        assertEquals(result.historyId(), service.find(filter).getFirst().historyId());
        assertEquals(9, service.count(filter));
    }

    @Test
    void thresholdAlertClientHandlesDisabledSuccessFailuresAndInterrupt() throws Exception {
        HttpTransport transport = mock(HttpTransport.class);
        ThresholdAlertProperties disabled = properties(false, List.of(), "");
        new ThresholdAlertClient(transport, new ObjectMapper(), disabled).send(result());
        verifyNoInteractions(transport);

        ThresholdAlertProperties enabled = properties(true, List.of("ops@example.com"), "Bearer secret");
        ThresholdAlertClient client = new ThresholdAlertClient(transport, new ObjectMapper(), enabled);
        when(transport.send(any())).thenReturn(201, 500);
        client.send(result());
        client.send(result());
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).send(request.capture());
        assertEquals("Bearer secret", request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertTrue(request.getValue().headers().firstValue("X-Correlation-Id").isPresent());

        doThrow(new IOException("offline")).when(transport).send(any());
        client.send(result());
        doThrow(new InterruptedException("stop")).when(transport).send(any());
        client.send(result());
        assertTrue(Thread.interrupted());

        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new IllegalStateException("json"));
        new ThresholdAlertClient(transport, broken, enabled).send(result());
    }

    @Test
    void jdkTransportReturnsStatus() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        int status = new JdkHttpTransport(httpClient).send(
                HttpRequest.newBuilder(URI.create("https://example.com")).build());
        assertEquals(204, status);
    }

    @Test
    void pollingJobCompletesSuccessfulAndFailedExecutions() throws Exception {
        DueScheduleClaimService claimService = mock(DueScheduleClaimService.class);
        ScheduleExecutionService executionService = mock(ScheduleExecutionService.class);
        AsyncExceptionHandler handler = mock(AsyncExceptionHandler.class);
        ScheduleRepository repository = mock(ScheduleRepository.class);
        WorkerIdentity identity = new WorkerIdentity();
        ScheduledExecution execution = execution();
        when(claimService.claimReadyExecutions(anyString())).thenReturn(List.of(execution));
        when(executionService.execute(execution)).thenReturn(ExecutionStatus.SUCCESS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SchedulerPollingJob job = new SchedulerPollingJob(claimService, executionService, handler,
                repository, identity, Clock.fixed(NOW, ZoneOffset.UTC), executor);
        job.poll();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        verify(repository).completeExecution(execution.id(), "SUCCESS", NOW, null, identity.value());

        reset(executionService, repository, handler);
        when(executionService.execute(execution)).thenThrow(new IllegalStateException("boom"));
        ExecutorService failedExecutor = Executors.newSingleThreadExecutor();
        new SchedulerPollingJob(claimService, executionService, handler, repository, identity,
                Clock.fixed(NOW, ZoneOffset.UTC), failedExecutor).poll();
        failedExecutor.shutdown();
        assertTrue(failedExecutor.awaitTermination(5, TimeUnit.SECONDS));
        verify(repository).completeExecution(execution.id(), "FAILED", NOW,
                "Schedule execution failed unexpectedly", identity.value());
        verify(handler).handle(anyString(), eq("scheduler.execution"), eq("virtual-thread"),
                eq("executeSchedule"), anyMap(), any(Throwable.class));

        reset(claimService, handler);
        when(claimService.claimReadyExecutions(anyString())).thenThrow(new IllegalStateException("db"));
        ExecutorService unusedExecutor = Executors.newSingleThreadExecutor();
        new SchedulerPollingJob(claimService, executionService, handler, repository, identity,
                Clock.fixed(NOW, ZoneOffset.UTC), unusedExecutor).poll();
        unusedExecutor.shutdownNow();
        verify(handler).handle(anyString(), eq("scheduler.polling"), eq("scheduler"),
                eq("pollDueSchedules"), anyMap(), any(Throwable.class));
    }

    @Test
    void exceptionHandlersAndWorkerIdentityAreUsable() {
        SchedulerConflictException first = new SchedulerConflictException("duplicate");
        SchedulerConflictException second = new SchedulerConflictException("duplicate", first);
        assertSame(first, second.getCause());
        assertEquals(HttpStatus.CONFLICT,
                new SchedulerExceptionHandler().handleConflict(first).getStatusCode());
        ErrorAlertNotifier notifier = mock(ErrorAlertNotifier.class);
        new AsyncExceptionHandler(notifier).handle(null, "dataset", "test", "run", null, first);
        new AsyncExceptionHandler(notifier).handle(
                "trace", "dataset", "test", "run", Map.of("id", 1), first);
        verify(notifier, times(2)).send(any(ErrorAlert.class));
        assertFalse(new WorkerIdentity().value().isBlank());
    }

    private static ThresholdAlertProperties properties(boolean enabled, List<String> recipients, String auth) {
        return new ThresholdAlertProperties(enabled, URI.create("https://alert.example.com/api/v1/alert"),
                "scheduler@example.com", "Scheduler", recipients, auth, Duration.ofSeconds(2));
    }

    private static TaskExecutionResult result() {
        return new TaskExecutionResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), "billing", ExecutionStatus.SUCCESS,
                NOW.minusMillis(50), NOW, 50, 25, true, 200, null);
    }

    private static ScheduledExecution execution() {
        ScheduleDefinition schedule = new ScheduleDefinition(UUID.randomUUID(), "schedule",
                ScheduleTargetType.TASK, UUID.randomUUID(), null, "0 * * * * *", ZoneOffset.UTC,
                true, NOW, NOW, NOW);
        return new ScheduledExecution(UUID.randomUUID(), schedule, NOW);
    }
}
