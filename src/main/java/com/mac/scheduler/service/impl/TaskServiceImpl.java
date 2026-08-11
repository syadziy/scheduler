package com.mac.scheduler.service.impl;

import com.mac.scheduler.config.properties.HttpTaskProperties;
import com.mac.scheduler.entities.dto.CreateTaskRequest;
import com.mac.scheduler.entities.dto.CreateTaskResponse;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.AuditEventPublisher;
import com.mac.scheduler.service.TaskService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TaskServiceImpl implements TaskService {

    private final TaskRepository repository;
    private final HttpTaskProperties properties;
    private final Clock clock;
    private final AuditEventPublisher auditEventPublisher;

    public TaskServiceImpl(
            TaskRepository repository,
            HttpTaskProperties properties,
            Clock clock,
            AuditEventPublisher auditEventPublisher) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public CreateTaskResponse create(CreateTaskRequest request) {
        validateEndpoint(request.endpoint());
        Duration timeout = request.timeout() == null
                ? properties.defaultReadTimeout()
                : request.timeout();
        validatePositive(timeout, "timeout");
        if (timeout.compareTo(properties.maxReadTimeout()) > 0) {
            throw new IllegalArgumentException(
                    "timeout: must not exceed " + properties.maxReadTimeout());
        }
        validatePositive(request.threshold(), "threshold");

        Instant now = clock.instant();
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                request.name().trim(),
                request.method(),
                request.endpoint(),
                normalizeHeaders(request.headers()),
                request.requestBody(),
                timeout,
                request.threshold(),
                request.enabled() == null || request.enabled(),
                now,
                now);
        repository.insert(task);
        auditEventPublisher.publishCreated("SCHEDULER_TASK_CREATED", "SCHEDULER_TASK", task.id());
        return new CreateTaskResponse(task.id(), task.name(), task.createdAt());
    }

    @Override
    public List<ScheduledTask> findAll() {
        return repository.findAll();
    }

    private void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint: only HTTP and HTTPS schemes are allowed");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("endpoint: host is required");
        }
        if (endpoint.toString().length() > 2_048) {
            throw new IllegalArgumentException("endpoint: must not exceed 2048 characters");
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("endpoint: embedded credentials are not allowed");
        }

        Set<String> allowedHosts = properties.allowedHosts();
        if (!allowedHosts.isEmpty()
                && allowedHosts.stream()
                        .filter(host -> host != null && !host.isBlank())
                        .noneMatch(host -> host.equalsIgnoreCase(endpoint.getHost()))) {
            throw new IllegalArgumentException("endpoint: host is not in scheduler.http.allowed-hosts");
        }
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return headers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> {
                    String name = entry.getKey().trim();
                    if (name.equalsIgnoreCase("Host")
                            || name.equalsIgnoreCase("Content-Length")
                            || name.equalsIgnoreCase("X-Correlation-Id")) {
                        throw new IllegalArgumentException(
                                "headers: " + name + " is managed by the scheduler");
                    }
                    return name;
                },
                entry -> entry.getValue().trim()));
    }

    private static void validatePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + ": must be greater than zero");
        }
    }
}
