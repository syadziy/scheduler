package com.mac.scheduler.service.impl;

import com.mac.scheduler.config.properties.AuditPublisherProperties;
import com.mac.scheduler.entities.dto.AuditEvent;
import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.AuditEventPublisher;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditPublisherProperties properties;
    private final ErrorAlertNotifier alertNotifier;
    private final Clock clock;

    public KafkaAuditEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            AuditPublisherProperties properties,
            ErrorAlertNotifier alertNotifier,
            Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.alertNotifier = alertNotifier;
        this.clock = clock;
    }

    @Override
    public void publishCreated(String action, String resourceType, UUID resourceId) {
        if (!properties.enabled()) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        String traceId = resolveTraceId(eventId);
        AuditEvent event = new AuditEvent(
                eventId,
                properties.sourceSystem(),
                clock.instant(),
                resolveActorId(),
                null,
                action,
                resourceType,
                resourceId.toString(),
                "SUCCESS",
                traceId,
                null,
                Map.of());
        try {
            kafkaTemplate.send(properties.topic(), eventId.toString(), event)
                    .whenComplete((result, exception) -> {
                        if (exception == null) {
                            StructuredLog.info(LOG, "Scheduler audit event published", Map.of(
                                    LogFields.EVENT_ACTION, "publishAuditEvent",
                                    LogFields.EVENT_OUTCOME, LogFields.OUTCOME_SUCCESS,
                                    LogFields.EVENT_DATASET, "scheduler.audit",
                                    "audit.event.id", eventId,
                                    "scheduler.resource.id", resourceId));
                        } else {
                            handleFailure(traceId, eventId, resourceId, exception);
                        }
                    });
        } catch (RuntimeException exception) {
            handleFailure(traceId, eventId, resourceId, exception);
        }
    }

    private void handleFailure(String traceId, UUID eventId, UUID resourceId, Throwable exception) {
        StructuredLog.error(LOG, "Scheduler audit event could not be published", Map.of(
                LogFields.EVENT_ACTION, "publishAuditEvent",
                LogFields.EVENT_OUTCOME, LogFields.OUTCOME_FAILURE,
                LogFields.EVENT_DATASET, "scheduler.audit",
                "audit.event.id", eventId,
                "scheduler.resource.id", resourceId), exception);
        alertNotifier.send(ErrorAlert.failure(traceId, "kafka", "publishAuditEvent"));
    }

    private String resolveActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() || authentication.getName().isBlank()
                ? properties.fallbackActorId()
                : authentication.getName();
    }

    private String resolveTraceId(UUID eventId) {
        String traceId = MDC.get(LogFields.TRACE_ID);
        return traceId == null || traceId.isBlank() ? eventId.toString() : traceId;
    }
}
