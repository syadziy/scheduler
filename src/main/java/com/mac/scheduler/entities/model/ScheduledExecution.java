package com.mac.scheduler.entities.model;

import java.time.Instant;
import java.util.UUID;

public record ScheduledExecution(
        UUID id,
        ScheduleDefinition schedule,
        Instant scheduledFor) {}
