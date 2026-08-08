package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.constant.SchedulerLogFields;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.TaskExecutor;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ScheduleExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleExecutionService.class);

    private final TaskRepository taskRepository;
    private final TaskGroupRepository groupRepository;
    private final TaskExecutor taskExecutor;
    private final ExecutorService executor;

    public ScheduleExecutionService(
            TaskRepository taskRepository,
            TaskGroupRepository groupRepository,
            TaskExecutor taskExecutor,
            @Qualifier("schedulerVirtualThreadExecutor") ExecutorService executor) {
        this.taskRepository = taskRepository;
        this.groupRepository = groupRepository;
        this.taskExecutor = taskExecutor;
        this.executor = executor;
    }

    public ExecutionStatus execute(ScheduledExecution execution) {
        ScheduleDefinition schedule = execution.schedule();
        UUID executionId = execution.id();
        Map<String, String> context = Map.of(
                LogFields.TRACE_ID, executionId.toString(),
                LogFields.EVENT_DATASET, "scheduler.execution");
        final ExecutionStatus[] status = new ExecutionStatus[1];
        StructuredLog.withMdc(
                context,
                () -> status[0] = executeWithContext(schedule, executionId));
        return status[0];
    }

    private ExecutionStatus executeWithContext(
            ScheduleDefinition schedule,
            UUID executionId) {
        StructuredLog.info(LOG, "Schedule execution started", Map.of(
                LogFields.EVENT_ACTION, "executeSchedule",
                LogFields.EVENT_DATASET, "scheduler.execution",
                SchedulerLogFields.SCHEDULE_ID, schedule.id(),
                SchedulerLogFields.EXECUTION_ID, executionId,
                SchedulerLogFields.TARGET_TYPE, schedule.targetType().name()));

        List<TaskExecutionResult> results = schedule.targetType() == ScheduleTargetType.TASK
                ? executeSingleTask(schedule, executionId)
                : executeGroup(schedule, executionId);
        long failures = results.stream()
                .filter(result -> result.status() == ExecutionStatus.FAILED)
                .count();
        StructuredLog.info(LOG, "Schedule execution completed", Map.of(
                LogFields.EVENT_ACTION, "executeSchedule",
                LogFields.EVENT_OUTCOME,
                failures == 0 ? LogFields.OUTCOME_SUCCESS : LogFields.OUTCOME_FAILURE,
                LogFields.EVENT_DATASET, "scheduler.execution",
                SchedulerLogFields.SCHEDULE_ID, schedule.id(),
                SchedulerLogFields.EXECUTION_ID, executionId,
                "scheduler.task.count", results.size(),
                "scheduler.task.failure_count", failures));
        return failures == 0
                ? ExecutionStatus.SUCCESS
                : ExecutionStatus.FAILED;
    }

    private List<TaskExecutionResult> executeSingleTask(
            ScheduleDefinition schedule,
            UUID executionId) {
        ScheduledTask task = taskRepository.findById(schedule.taskId())
                .orElseThrow(() -> new IllegalStateException("Scheduled task no longer exists"));
        if (!task.enabled()) {
            throw new IllegalStateException("Scheduled task is disabled");
        }
        return List.of(taskExecutor.execute(task, schedule, executionId, null));
    }

    private List<TaskExecutionResult> executeGroup(
            ScheduleDefinition schedule,
            UUID executionId) {
        TaskGroup group = groupRepository.findById(schedule.groupId())
                .orElseThrow(() -> new IllegalStateException("Scheduled task group no longer exists"));
        if (!group.enabled()) {
            throw new IllegalStateException("Scheduled task group is disabled");
        }
        List<TaskExecutionResult> results = executeGroupDefinition(group, schedule, executionId);
        if (results.isEmpty()) {
            throw new IllegalStateException("Scheduled task group has no enabled tasks");
        }
        return results;
    }

    private List<TaskExecutionResult> executeGroupDefinition(
            TaskGroup group,
            ScheduleDefinition schedule,
            UUID executionId) {
        if (!group.enabled()) {
            return List.of();
        }
        List<Supplier<List<TaskExecutionResult>>> operations = new ArrayList<>();
        group.tasks().stream()
                .filter(ScheduledTask::enabled)
                .map(task -> (Supplier<List<TaskExecutionResult>>) () -> List.of(
                        taskExecutor.execute(task, schedule, executionId, group.id())))
                .forEach(operations::add);
        group.groups().stream()
                .filter(TaskGroup::enabled)
                .map(childGroup -> (Supplier<List<TaskExecutionResult>>) () ->
                        executeGroupDefinition(childGroup, schedule, executionId))
                .forEach(operations::add);
        if (group.executionMode() == GroupExecutionMode.SERIAL) {
            return operations.stream()
                    .flatMap(operation -> operation.get().stream())
                    .toList();
        }
        return executeParallel(operations);
    }

    private List<TaskExecutionResult> executeParallel(
            List<Supplier<List<TaskExecutionResult>>> operations) {
        Map<String, String> parentContext = StructuredLog.copyMdc();
        List<Future<List<TaskExecutionResult>>> futures = operations.stream()
                .map(operation -> executor.submit(() -> {
                    AtomicReference<List<TaskExecutionResult>> result = new AtomicReference<>();
                    StructuredLog.withMdc(
                            parentContext,
                            () -> result.set(operation.get()));
                    return result.get();
                }))
                .toList();
        List<TaskExecutionResult> results = new ArrayList<>();
        for (Future<List<TaskExecutionResult>> future : futures) {
            try {
                results.addAll(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.forEach(item -> item.cancel(true));
                throw new IllegalStateException("Parallel task group was interrupted", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Parallel task execution failed unexpectedly", exception.getCause());
            }
        }
        return List.copyOf(results);
    }
}
