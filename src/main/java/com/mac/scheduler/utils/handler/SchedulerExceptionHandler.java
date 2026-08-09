package com.mac.scheduler.utils.handler;

import com.mac.scheduler.utils.exception.SchedulerConflictException;
import com.mac.sdk_util.entities.dto.ResponseDTO;
import com.mac.sdk_util.helper.ResponseHelper;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchedulerExceptionHandler {

    @ExceptionHandler(SchedulerConflictException.class)
    public ResponseEntity<ResponseDTO<Map<String, String>>> handleConflict(
            SchedulerConflictException exception) {
        return ResponseHelper.httpConflict(Map.of("reason", exception.getMessage()));
    }
}
