package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mac.scheduler.config.properties.AuditPublisherProperties;
import com.mac.scheduler.entities.dto.AuditEvent;
import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.sdk_util.entities.constant.LogFields;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaAuditEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @AfterEach
    void clearContext() {
        MDC.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void publishesCreateEventUsingTraceAndFallbackActor() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        ErrorAlertNotifier notifier = mock(ErrorAlertNotifier.class);
        SendResult<String, Object> result = new SendResult<>(
                new ProducerRecord<>("centralized-audit.requested", "key", new Object()), null);
        when(template.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        MDC.put(LogFields.TRACE_ID, "trace-1");
        UUID resourceId = UUID.randomUUID();

        new KafkaAuditEventPublisher(template, properties(true), notifier,
                Clock.fixed(NOW, ZoneOffset.UTC))
                .publishCreated("SCHEDULER_TASK_CREATED", "SCHEDULER_TASK", resourceId);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(template).send(eq("centralized-audit.requested"), anyString(), eventCaptor.capture());
        AuditEvent event = (AuditEvent) eventCaptor.getValue();
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.actorId()).isEqualTo("scheduler-service");
        assertThat(event.resourceId()).isEqualTo(resourceId.toString());
        assertThat(event.outcome()).isEqualTo("SUCCESS");
        verify(notifier, never()).send(any(ErrorAlert.class));
    }

    @Test
    void alertsWhenAsyncOrSynchronousKafkaSendFailsAndCanBeDisabled() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        ErrorAlertNotifier notifier = mock(ErrorAlertNotifier.class);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(template.send(anyString(), anyString(), any()))
                .thenReturn(failed)
                .thenThrow(new IllegalStateException("producer closed"));
        KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(
                template, properties(true), notifier, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishCreated("CREATE", "TASK", UUID.randomUUID());
        publisher.publishCreated("CREATE", "TASK", UUID.randomUUID());
        verify(notifier, org.mockito.Mockito.times(2)).send(any(ErrorAlert.class));

        new KafkaAuditEventPublisher(template, properties(false), notifier,
                Clock.fixed(NOW, ZoneOffset.UTC)).publishCreated("CREATE", "TASK", UUID.randomUUID());
        verify(template, org.mockito.Mockito.times(2)).send(anyString(), anyString(), any());
    }

    private static AuditPublisherProperties properties(boolean enabled) {
        return new AuditPublisherProperties(
                enabled, "centralized-audit.requested", "SCHEDULER-SERVICE", "scheduler-service");
    }
}
