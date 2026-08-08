package com.mac.scheduler.entities.dto;

import com.mac.scheduler.entities.constant.ScheduleTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateScheduleRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull ScheduleTargetType targetType,
        UUID taskId,
        UUID groupId,
        @NotBlank @Size(max = 120) String cronExpression,
        @NotBlank @Size(max = 80) String zoneId,
        Boolean enabled) {}
