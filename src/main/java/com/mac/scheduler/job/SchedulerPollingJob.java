package com.mac.scheduler.job;

import com.mac.scheduler.entities.constant.SchedulerLogFields;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.repository.ScheduleRepository;
import com.mac.scheduler.service.impl.DueScheduleClaimService;
import com.mac.scheduler.service.impl.ScheduleExecutionService;
import com.mac.scheduler.utils.handler.AsyncExceptionHandler;
import com.mac.scheduler.utils.WorkerIdentity;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "scheduler.engine",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulerPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulerPollingJob.class);

    private final DueScheduleClaimService claimService;
    private final ScheduleExecutionService executionService;
    private final AsyncExceptionHandler exceptionHandler;
    private final ScheduleRepository scheduleRepository;
    private final WorkerIdentity workerIdentity;
    private final Clock clock;
    private final ExecutorService executor;

    public SchedulerPollingJob(
            DueScheduleClaimService claimService,
            ScheduleExecutionService executionService,
            AsyncExceptionHandler exceptionHandler,
            ScheduleRepository scheduleRepository,
            WorkerIdentity workerIdentity,
            Clock clock,
            @Qualifier("schedulerVirtualThreadExecutor") ExecutorService executor) {
        this.claimService = claimService;
        this.executionService = executionService;
        this.exceptionHandler = exceptionHandler;
        this.scheduleRepository = scheduleRepository;
        this.workerIdentity = workerIdentity;
        this.clock = clock;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${scheduler.engine.poll-interval:PT5S}")
    public void poll() {
        String pollTraceId = UUID.randomUUID().toString();
        StructuredLog.withMdc(
                Map.of(
                        LogFields.TRACE_ID, pollTraceId,
                        LogFields.EVENT_DATASET, "scheduler.polling"),
                () -> pollWithContext(pollTraceId));
    }

    private void pollWithContext(String pollTraceId) {
        try {
            List<ScheduledExecution> readyExecutions = claimService.claimReadyExecutions(
                    workerIdentity.value());
            if (!readyExecutions.isEmpty()) {
                StructuredLog.info(LOG, "Schedule executions claimed", Map.of(
                        LogFields.EVENT_ACTION, "claimReadyExecutions",
                        LogFields.EVENT_OUTCOME, LogFields.OUTCOME_SUCCESS,
                        LogFields.EVENT_DATASET, "scheduler.polling",
                        "scheduler.execution.count", readyExecutions.size()));
            }
            readyExecutions.forEach(execution -> submit(execution, pollTraceId));
        } catch (Exception exception) {
            exceptionHandler.handle(
                    pollTraceId,
                    "scheduler.polling",
                    "scheduler",
                    "pollDueSchedules",
                    Map.of(),
                    exception);
        }
    }

    private void submit(ScheduledExecution execution, String parentTraceId) {
        executor.submit(() -> {
            try {
                ExecutionStatus status = executionService.execute(execution);
                scheduleRepository.completeExecution(
                        execution.id(),
                        status.name(),
                        clock.instant(),
                        null,
                        workerIdentity.value());
            } catch (Throwable exception) {
                try {
                    scheduleRepository.completeExecution(
                            execution.id(),
                            ExecutionStatus.FAILED.name(),
                            clock.instant(),
                            "Schedule execution failed unexpectedly",
                            workerIdentity.value());
                } catch (Exception completionException) {
                    exception.addSuppressed(completionException);
                }
                exceptionHandler.handle(
                        parentTraceId,
                        "scheduler.execution",
                        "virtual-thread",
                        "executeSchedule",
                        Map.of(
                                SchedulerLogFields.SCHEDULE_ID, execution.schedule().id(),
                                SchedulerLogFields.EXECUTION_ID, execution.id()),
                        exception);
                if (exception instanceof Error error) {
                    throw error;
                }
            }
        });
    }
}
