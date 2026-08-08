package com.mac.scheduler.entities.dto;

import com.mac.scheduler.entities.constant.HttpMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record CreateTaskRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull HttpMethod method,
        @NotNull URI endpoint,
        @Size(max = 100) Map<@NotBlank String, @NotBlank String> headers,
        @Size(max = 1_000_000) String requestBody,
        Duration timeout,
        @NotNull Duration threshold,
        Boolean enabled) {}
