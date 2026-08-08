package com.mac.scheduler.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("scheduler.audit")
public record AuditPublisherProperties(
        boolean enabled,
        @NotBlank String topic,
        @NotBlank String sourceSystem,
        @NotBlank String fallbackActorId) {

    public AuditPublisherProperties {
        topic = topic == null ? "centralized-audit.requested" : topic;
        sourceSystem = sourceSystem == null ? "SCHEDULER-SERVICE" : sourceSystem;
        fallbackActorId = fallbackActorId == null ? "scheduler-service" : fallbackActorId;
    }
}
