package com.mac.scheduler.service.impl;

import com.mac.scheduler.config.properties.ErrorAlertProperties;
import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.scheduler.service.HttpTransport;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class CentralizedErrorAlertClient implements ErrorAlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(CentralizedErrorAlertClient.class);
    private final HttpTransport httpTransport;
    private final ObjectMapper objectMapper;
    private final ErrorAlertProperties properties;

    public CentralizedErrorAlertClient(
            HttpTransport httpTransport,
            ObjectMapper objectMapper,
            ErrorAlertProperties properties) {
        this.httpTransport = httpTransport;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void send(ErrorAlert alert) {
        if (!properties.enabled() || properties.recipients().isEmpty()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(properties.endpoint())
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("X-Correlation-Id", alert.correlationId())
                    .POST(HttpRequest.BodyPublishers.ofString(payload(alert)));
            if (!properties.authorizationHeader().isBlank()) {
                builder.header("Authorization", properties.authorizationHeader());
            }
            int status = httpTransport.send(builder.build());
            if (status < 200 || status >= 300) {
                logFailure(alert, "Centralized alert returned a non-success status", null, status);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(alert, "Error alert request was interrupted", exception, null);
        } catch (IOException | RuntimeException exception) {
            logFailure(alert, "Error alert could not be delivered", exception, null);
        }
    }

    private String payload(ErrorAlert alert) throws JacksonException {
        List<Map<String, String>> recipients = properties.recipients().stream()
                .map(email -> Map.of("type", "TO", "email", email))
                .toList();
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("sourceSystem", "SCHEDULER-SERVICE"),
                Map.entry("idempotencyKey", alert.idempotencyKey()),
                Map.entry("correlationId", alert.correlationId()),
                Map.entry("senderEmail", properties.senderEmail()),
                Map.entry("senderName", properties.senderName()),
                Map.entry("subject", alert.subject()),
                Map.entry("body", alert.body()),
                Map.entry("bodyType", "TEXT"),
                Map.entry("priority", 1),
                Map.entry("recipients", recipients),
                Map.entry("attachments", List.of())));
    }

    private void logFailure(ErrorAlert alert, String message, Throwable exception, Integer status) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(LogFields.EVENT_ACTION, "sendErrorAlert");
        fields.put(LogFields.EVENT_OUTCOME, LogFields.OUTCOME_FAILURE);
        fields.put(LogFields.EVENT_DATASET, "scheduler.error-alert");
        fields.put("alert.idempotency_key", alert.idempotencyKey());
        if (status != null) {
            fields.put("http.response.status_code", status);
        }
        if (exception == null) {
            StructuredLog.warn(LOG, message, fields);
        } else {
            StructuredLog.error(LOG, message, fields, exception);
        }
    }
}
