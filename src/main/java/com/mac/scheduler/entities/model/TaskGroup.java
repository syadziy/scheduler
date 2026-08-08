package com.mac.scheduler.entities.model;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskGroup(
        UUID id,
        String name,
        GroupExecutionMode executionMode,
        boolean enabled,
        List<ScheduledTask> tasks,
        Instant createdAt,
        Instant updatedAt) {

    public TaskGroup {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
