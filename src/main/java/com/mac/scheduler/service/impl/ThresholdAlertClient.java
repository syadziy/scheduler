package com.mac.scheduler.service.impl;

import com.mac.scheduler.config.properties.ThresholdAlertProperties;
import com.mac.scheduler.entities.constant.SchedulerLogFields;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.service.ThresholdAlertNotifier;
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
public class ThresholdAlertClient implements ThresholdAlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ThresholdAlertClient.class);

    private final HttpTransport httpTransport;
    private final ObjectMapper objectMapper;
    private final ThresholdAlertProperties properties;

    public ThresholdAlertClient(
            HttpTransport httpTransport,
            ObjectMapper objectMapper,
            ThresholdAlertProperties properties) {
        this.httpTransport = httpTransport;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void send(TaskExecutionResult result) {
        if (!properties.enabled() || properties.recipients().isEmpty()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(properties.endpoint())
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("X-Correlation-Id", result.executionId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(result)));
            if (!properties.authorizationHeader().isBlank()) {
                builder.header("Authorization", properties.authorizationHeader());
            }
            int statusCode = httpTransport.send(builder.build());
            if (statusCode < 200 || statusCode >= 300) {
                logFailure(result, "Centralized alert returned status " + statusCode, null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(result, "Threshold alert request was interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            logFailure(result, "Threshold alert could not be delivered", exception);
        }
    }

    private String buildPayload(TaskExecutionResult result) throws JacksonException {
        List<Map<String, String>> recipients = properties.recipients().stream()
                .map(email -> Map.of("type", "TO", "email", email))
                .toList();
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("sourceSystem", "SCHEDULER-SERVICE"),
                Map.entry("idempotencyKey", "scheduler-threshold-" + result.historyId()),
                Map.entry("correlationId", result.executionId().toString()),
                Map.entry("senderEmail", properties.senderEmail()),
                Map.entry("senderName", properties.senderName()),
                Map.entry("subject", "Scheduler task exceeded execution threshold"),
                Map.entry(
                        "body",
                        "Task %s (%s) ran for %d ms and exceeded its %d ms threshold."
                                .formatted(
                                        result.taskName(),
                                        result.taskId(),
                                        result.durationMs(),
                                        result.thresholdMs())),
                Map.entry("bodyType", "TEXT"),
                Map.entry("priority", 3),
                Map.entry("recipients", recipients),
                Map.entry("attachments", List.of()));
        return objectMapper.writeValueAsString(payload);
    }

    private void logFailure(TaskExecutionResult result, String message, Throwable exception) {
        Map<String, Object> fields = Map.of(
                LogFields.EVENT_ACTION, "sendThresholdAlert",
                LogFields.EVENT_OUTCOME, LogFields.OUTCOME_FAILURE,
                LogFields.EVENT_DATASET, "scheduler.threshold-alert",
                SchedulerLogFields.EXECUTION_ID, result.executionId(),
                SchedulerLogFields.TASK_ID, result.taskId());
        if (exception == null) {
            StructuredLog.warn(LOG, message, fields);
        } else {
            StructuredLog.error(LOG, message, fields, exception);
        }
    }
}
