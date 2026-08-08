package com.mac.scheduler.utils.handler;

import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.sdk_util.entities.constant.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ErrorAlertFilter extends OncePerRequestFilter {

    private final ErrorAlertNotifier notifier;

    public ErrorAlertFilter(ErrorAlertNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean notified = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            notifyError(request, "httpRequestException");
            notified = true;
            throw exception;
        } finally {
            if (!notified && response.getStatus() >= 500) {
                notifyError(request, "httpResponse" + response.getStatus());
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    private void notifyError(HttpServletRequest request, String action) {
        String traceId = MDC.get(LogFields.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader("X-Correlation-Id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        notifier.send(ErrorAlert.failure(traceId, "http", action));
    }
}
