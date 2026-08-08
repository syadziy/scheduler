package com.mac.scheduler.entities.model;

import com.mac.scheduler.entities.constant.ExecutionStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskExecutionResult(
        UUID historyId,
        UUID executionId,
        UUID scheduleId,
        UUID groupId,
        UUID taskId,
        String taskName,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        long thresholdMs,
        boolean thresholdExceeded,
        Integer httpStatusCode,
        String errorMessage) {}
