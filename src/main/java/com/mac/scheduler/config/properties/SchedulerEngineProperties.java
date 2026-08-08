package com.mac.scheduler.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "scheduler.engine")
public record SchedulerEngineProperties(
        boolean enabled,
        @NotNull Duration pollInterval,
        @NotNull Duration executionTimeout,
        @Min(1) int claimBatchSize,
        @Min(1) int maxParallelism) {

    public SchedulerEngineProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        executionTimeout = executionTimeout == null ? Duration.ofHours(1) : executionTimeout;
        claimBatchSize = claimBatchSize < 1 ? 20 : claimBatchSize;
        maxParallelism = maxParallelism < 1 ? 50 : maxParallelism;
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("scheduler.engine.poll-interval must be positive");
        }
        if (executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("scheduler.engine.execution-timeout must be positive");
        }
    }
}
