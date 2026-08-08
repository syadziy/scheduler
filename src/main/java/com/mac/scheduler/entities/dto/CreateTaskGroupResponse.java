package com.mac.scheduler.entities.dto;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateTaskGroupResponse(
        UUID groupId,
        String name,
        GroupExecutionMode executionMode,
        List<UUID> taskIds,
        List<UUID> groupIds,
        Instant createdAt) {}
