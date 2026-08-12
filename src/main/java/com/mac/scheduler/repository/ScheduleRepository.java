package com.mac.scheduler.repository;

import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduleRepository {

    ScheduleDefinition insert(ScheduleDefinition schedule);

    default List<ScheduleDefinition> findAll() {
        return List.of();
    }

    default List<ScheduleDefinition> findAll(int limit, int offset) {
        return findAll().stream().skip(offset).limit(limit).toList();
    }

    default long count() {
        return findAll().size();
    }

    List<ScheduleDefinition> findDueForUpdate(Instant dueAt, int limit);

    void updateNextExecution(ScheduleDefinition schedule, Instant nextExecutionAt, Instant updatedAt);

    void enqueueExecution(
            UUID executionId,
            ScheduleDefinition schedule,
            Instant scheduledFor,
            Instant createdAt);

    List<ScheduledExecution> claimPendingExecutions(
            Instant now,
            Instant staleBefore,
            int limit,
            String workerId);

    void completeExecution(
            UUID executionId,
            String status,
            Instant completedAt,
            String errorMessage,
            String workerId);
}
