package com.mac.scheduler.entities.model;

import com.mac.scheduler.entities.constant.ScheduleTargetType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record ScheduleDefinition(
        UUID id,
        String name,
        ScheduleTargetType targetType,
        UUID taskId,
        UUID groupId,
        String cronExpression,
        ZoneId zoneId,
        boolean enabled,
        Instant nextExecutionAt,
        Instant createdAt,
        Instant updatedAt) {}
