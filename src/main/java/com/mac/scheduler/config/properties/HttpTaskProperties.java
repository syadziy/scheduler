package com.mac.scheduler.config.properties;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "scheduler.http")
public record HttpTaskProperties(
        @NotNull Duration connectTimeout,
        @NotNull Duration defaultReadTimeout,
        @NotNull Duration maxReadTimeout,
        Set<String> allowedHosts) {

    public HttpTaskProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        defaultReadTimeout = defaultReadTimeout == null ? Duration.ofSeconds(30) : defaultReadTimeout;
        maxReadTimeout = maxReadTimeout == null ? Duration.ofMinutes(10) : maxReadTimeout;
        allowedHosts = allowedHosts == null
                ? Set.of()
                : allowedHosts.stream()
                        .filter(host -> host != null && !host.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toUnmodifiableSet());
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("scheduler.http.connect-timeout must be positive");
        }
        if (defaultReadTimeout.isZero() || defaultReadTimeout.isNegative()) {
            throw new IllegalArgumentException("scheduler.http.default-read-timeout must be positive");
        }
        if (maxReadTimeout.isZero() || maxReadTimeout.isNegative()) {
            throw new IllegalArgumentException("scheduler.http.max-read-timeout must be positive");
        }
        if (defaultReadTimeout.compareTo(maxReadTimeout) > 0) {
            throw new IllegalArgumentException(
                    "scheduler.http.default-read-timeout must not exceed max-read-timeout");
        }
    }
}
