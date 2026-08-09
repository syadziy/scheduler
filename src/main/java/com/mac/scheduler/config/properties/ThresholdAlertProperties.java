package com.mac.scheduler.config.properties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "scheduler.threshold-alert")
public record ThresholdAlertProperties(
        boolean enabled,
        @NotNull URI endpoint,
        @NotBlank @Email String senderEmail,
        @NotBlank String senderName,
        List<@Email String> recipients,
        String authorizationHeader,
        @NotNull Duration timeout) {

    public ThresholdAlertProperties {
        endpoint = endpoint == null ? URI.create("http://localhost:9003/api/v1/alert") : endpoint;
        senderEmail = senderEmail == null ? "scheduler@example.com" : senderEmail;
        senderName = senderName == null ? "Centralized Scheduler" : senderName;
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        authorizationHeader = authorizationHeader == null ? "" : authorizationHeader.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("scheduler.threshold-alert.timeout must be positive");
        }
    }
}
