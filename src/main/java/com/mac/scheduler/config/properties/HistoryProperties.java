package com.mac.scheduler.config.properties;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "scheduler.history")
public record HistoryProperties(@NotNull Duration maxRange) {

    public HistoryProperties {
        maxRange = maxRange == null ? Duration.ofDays(31) : maxRange;
        if (maxRange.isZero() || maxRange.isNegative()) {
            throw new IllegalArgumentException("scheduler.history.max-range must be positive");
        }
    }
}
