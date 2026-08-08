package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.entities.constant.SchedulerLogFields;
import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.repository.TaskHistoryRepository;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.scheduler.service.HttpTransport;
import com.mac.scheduler.service.TaskExecutor;
import com.mac.scheduler.service.ThresholdAlertNotifier;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HttpTaskExecutor implements TaskExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(HttpTaskExecutor.class);
    private static final String DATASET = "scheduler.task";

    private final HttpTransport httpTransport;
    private final TaskHistoryRepository historyRepository;
    private final ThresholdAlertNotifier thresholdAlertNotifier;
    private final ErrorAlertNotifier errorAlertNotifier;
    private final Semaphore executionPermits;
    private final Clock clock;

    public HttpTaskExecutor(
            HttpTransport httpTransport,
            TaskHistoryRepository historyRepository,
            ThresholdAlertNotifier thresholdAlertNotifier,
            ErrorAlertNotifier errorAlertNotifier,
            Semaphore executionPermits,
            Clock clock) {
        this.httpTransport = httpTransport;
        this.historyRepository = historyRepository;
        this.thresholdAlertNotifier = thresholdAlertNotifier;
        this.errorAlertNotifier = errorAlertNotifier;
        this.executionPermits = executionPermits;
        this.clock = clock;
    }

    @Override
    public TaskExecutionResult execute(
            ScheduledTask task,
            ScheduleDefinition schedule,
            UUID executionId,
            UUID groupId) {
        boolean acquired = false;
        try {
            executionPermits.acquire();
            acquired = true;
            return executeWithPermit(task, schedule, executionId, groupId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task execution was interrupted before it started", exception);
        } finally {
            if (acquired) {
                executionPermits.release();
            }
        }
    }

    private TaskExecutionResult executeWithPermit(
            ScheduledTask task,
            ScheduleDefinition schedule,
            UUID executionId,
            UUID groupId) {
        UUID historyId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        Integer statusCode = null;
        ExecutionStatus status = ExecutionStatus.SUCCESS;
        String errorMessage = null;
        Throwable failureCause = null;

        try {
            statusCode = httpTransport.send(buildRequest(task, executionId));
            if (statusCode < 200 || statusCode >= 300) {
                status = ExecutionStatus.FAILED;
                errorMessage = "HTTP request returned status " + statusCode;
            }
        } catch (HttpTimeoutException exception) {
            status = ExecutionStatus.FAILED;
            errorMessage = "HTTP request timed out";
            failureCause = exception;
        } catch (IOException exception) {
            status = ExecutionStatus.FAILED;
            errorMessage = "HTTP request failed";
            failureCause = exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = ExecutionStatus.FAILED;
            errorMessage = "HTTP request was interrupted";
            failureCause = exception;
        } catch (RuntimeException exception) {
            status = ExecutionStatus.FAILED;
            errorMessage = "HTTP request could not be executed";
            failureCause = exception;
        }

        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        Instant completedAt = clock.instant();
        long thresholdMs = task.threshold().toMillis();
        boolean thresholdExceeded = durationMs > thresholdMs;
        TaskExecutionResult result = new TaskExecutionResult(
                historyId,
                executionId,
                schedule.id(),
                groupId,
                task.id(),
                task.name(),
                status,
                startedAt,
                completedAt,
                durationMs,
                thresholdMs,
                thresholdExceeded,
                statusCode,
                errorMessage);
        historyRepository.insert(result);
        logResult(result, failureCause);
        if (thresholdExceeded) {
            thresholdAlertNotifier.send(result);
        }
        if (status == ExecutionStatus.FAILED) {
            errorAlertNotifier.send(ErrorAlert.failure(
                    executionId.toString(), "task-execution", "executeHttpTask"));
        }
        return result;
    }

    private HttpRequest buildRequest(ScheduledTask task, UUID executionId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(task.endpoint())
                .timeout(task.timeout())
                .header("X-Correlation-Id", executionId.toString());
        task.headers().forEach(builder::header);
        HttpRequest.BodyPublisher publisher = task.requestBody() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(task.requestBody());
        return builder.method(task.method().name(), publisher).build();
    }

    private void logResult(TaskExecutionResult result, Throwable failureCause) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LogFields.EVENT_ACTION, "executeHttpTask");
        fields.put(
                LogFields.EVENT_OUTCOME,
                result.status() == ExecutionStatus.SUCCESS
                        ? LogFields.OUTCOME_SUCCESS
                        : LogFields.OUTCOME_FAILURE);
        fields.put(LogFields.EVENT_DATASET, DATASET);
        fields.put(SchedulerLogFields.SCHEDULE_ID, result.scheduleId());
        fields.put(SchedulerLogFields.EXECUTION_ID, result.executionId());
        fields.put(SchedulerLogFields.TASK_ID, result.taskId());
        fields.put(SchedulerLogFields.TASK_NAME, result.taskName());
        fields.put(SchedulerLogFields.DURATION_MS, result.durationMs());
        fields.put(SchedulerLogFields.THRESHOLD_MS, result.thresholdMs());
        fields.put(SchedulerLogFields.THRESHOLD_EXCEEDED, result.thresholdExceeded());
        if (result.groupId() != null) {
            fields.put(SchedulerLogFields.GROUP_ID, result.groupId());
        }
        if (result.httpStatusCode() != null) {
            fields.put(SchedulerLogFields.HTTP_STATUS_CODE, result.httpStatusCode());
        }
        if (result.status() == ExecutionStatus.SUCCESS) {
            StructuredLog.info(LOG, "Scheduled HTTP task completed", fields);
        } else if (failureCause != null) {
            StructuredLog.error(LOG, "Scheduled HTTP task failed", fields, failureCause);
        } else {
            StructuredLog.warn(LOG, "Scheduled HTTP task failed", fields);
        }
    }
}
