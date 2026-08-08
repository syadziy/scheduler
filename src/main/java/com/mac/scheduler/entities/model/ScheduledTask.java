package com.mac.scheduler.entities.model;

import com.mac.scheduler.entities.constant.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String name,
        HttpMethod method,
        URI endpoint,
        Map<String, String> headers,
        String requestBody,
        Duration timeout,
        Duration threshold,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public ScheduledTask {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
