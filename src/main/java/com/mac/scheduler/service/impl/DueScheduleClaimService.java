package com.mac.scheduler.service.impl;

import com.mac.scheduler.config.properties.SchedulerEngineProperties;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.repository.ScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DueScheduleClaimService {

    private final ScheduleRepository repository;
    private final ScheduleCalculator calculator;
    private final SchedulerEngineProperties properties;
    private final Clock clock;

    public DueScheduleClaimService(
            ScheduleRepository repository,
            ScheduleCalculator calculator,
            SchedulerEngineProperties properties,
            Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ScheduledExecution> claimReadyExecutions(String workerId) {
        Instant now = clock.instant();
        List<ScheduleDefinition> dueSchedules = repository.findDueForUpdate(
                now,
                properties.claimBatchSize());
        for (ScheduleDefinition schedule : dueSchedules) {
            repository.enqueueExecution(
                    UUID.randomUUID(),
                    schedule,
                    schedule.nextExecutionAt(),
                    now);
            Instant nextExecutionAt = calculator.nextExecution(
                    schedule.cronExpression(),
                    schedule.zoneId(),
                    now);
            repository.updateNextExecution(schedule, nextExecutionAt, now);
        }
        return repository.claimPendingExecutions(
                now,
                now.minus(properties.executionTimeout()),
                properties.claimBatchSize(),
                workerId);
    }
}
