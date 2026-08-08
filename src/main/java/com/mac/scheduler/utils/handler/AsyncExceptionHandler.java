package com.mac.scheduler.utils.handler;

import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.sdk_util.entities.constant.LogFields;
import com.mac.sdk_util.utils.StructuredLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AsyncExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncExceptionHandler.class);
    private final ErrorAlertNotifier alertNotifier;

    public AsyncExceptionHandler(ErrorAlertNotifier alertNotifier) {
        this.alertNotifier = alertNotifier;
    }

    public void handle(
            String traceId,
            String dataset,
            String source,
            String action,
            Map<String, Object> additionalFields,
            Throwable exception) {
        Map<String, String> context = StructuredLog.copyMdc();
        context.put(
                LogFields.TRACE_ID,
                traceId == null || traceId.isBlank()
                        ? context.getOrDefault(LogFields.TRACE_ID, UUID.randomUUID().toString())
                        : traceId);
        context.put(LogFields.EVENT_DATASET, dataset);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LogFields.EVENT_ACTION, action);
        fields.put(LogFields.EVENT_OUTCOME, LogFields.OUTCOME_FAILURE);
        fields.put(LogFields.EVENT_DATASET, dataset);
        fields.put("scheduler.async.source", source);
        if (additionalFields != null) {
            fields.putAll(additionalFields);
        }

        StructuredLog.withMdc(
                context,
                () -> StructuredLog.error(LOG, "Asynchronous operation failed", fields, exception));
        alertNotifier.send(ErrorAlert.failure(
                context.get(LogFields.TRACE_ID), source, action));
    }
}
