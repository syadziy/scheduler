package com.mac.scheduler.entities.model;

import java.time.Instant;
import java.util.UUID;

public record HistoryFilter(
        Instant from,
        Instant to,
        UUID groupId,
        UUID taskId,
        Boolean thresholdExceeded,
        int limit,
        long offset) {}
