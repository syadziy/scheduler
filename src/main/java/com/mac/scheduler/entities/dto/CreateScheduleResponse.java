package com.mac.scheduler.entities.dto;

import com.mac.scheduler.entities.constant.ScheduleTargetType;
import java.time.Instant;
import java.util.UUID;

public record CreateScheduleResponse(
        UUID scheduleId,
        String name,
        ScheduleTargetType targetType,
        UUID targetId,
        Instant nextExecutionAt,
        Instant createdAt) {}
