package com.mac.scheduler.entities.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateTaskResponse(UUID taskId, String name, Instant createdAt) {}
