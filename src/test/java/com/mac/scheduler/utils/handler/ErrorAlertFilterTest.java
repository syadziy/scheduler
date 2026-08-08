package com.mac.scheduler.utils.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.service.ErrorAlertNotifier;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ErrorAlertFilterTest {

    @Test
    void notifiesForServerResponseAndEscapedException() throws Exception {
        ErrorAlertNotifier notifier = mock(ErrorAlertNotifier.class);
        ErrorAlertFilter filter = new ErrorAlertFilter(notifier);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("X-Correlation-Id", "trace-http");
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) ->
                ((MockHttpServletResponse) response).setStatus(500));
        verify(notifier).send(any(ErrorAlert.class));
        assertThatThrownBy(() -> filter.doFilter(
                request, new MockHttpServletResponse(), (req, response) -> {
                    throw new ServletException("failed");
                })).isInstanceOf(ServletException.class);
    }

    @Test
    void skipsHealthyAndActuatorRequests() throws Exception {
        ErrorAlertNotifier notifier = mock(ErrorAlertNotifier.class);
        ErrorAlertFilter filter = new ErrorAlertFilter(notifier);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/tasks"),
                new MockHttpServletResponse(), (request, response) -> {});
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), (request, response) ->
                        ((MockHttpServletResponse) response).setStatus(500));
        verify(notifier, never()).send(any(ErrorAlert.class));
    }
}
