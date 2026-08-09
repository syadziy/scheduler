package com.mac.scheduler.controller;

import com.mac.scheduler.entities.dto.TaskHistoryResponse;
import com.mac.scheduler.config.properties.HistoryProperties;
import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.service.HistoryService;
import com.mac.sdk_util.entities.dto.PagingDTO;
import com.mac.sdk_util.entities.dto.ResponseDTO;
import com.mac.sdk_util.utils.ResponsePagingHelper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/histories")
public class TaskHistoryController {

    private final HistoryService historyService;
    private final ZoneId applicationZone;
    private final Clock clock;
    private final HistoryProperties properties;

    public TaskHistoryController(
            HistoryService historyService,
            ZoneId applicationZone,
            Clock clock,
            HistoryProperties properties) {
        this.historyService = historyService;
        this.applicationZone = applicationZone;
        this.clock = clock;
        this.properties = properties;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_scheduler:read')")
    public ResponseEntity<ResponseDTO<List<TaskHistoryResponse>>> findHistory(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) Boolean thresholdExceeded,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) long offset) {
        HistoryFilter filter = createFilter(
                date,
                from,
                to,
                groupId,
                taskId,
                thresholdExceeded,
                limit,
                offset);
        List<TaskHistoryResponse> history = historyService.find(filter);
        long total = historyService.count(filter);
        return ResponsePagingHelper.httpOK(history, new PagingDTO(limit, offset, total));
    }

    private HistoryFilter createFilter(
            LocalDate date,
            Instant from,
            Instant to,
            UUID groupId,
            UUID taskId,
            Boolean thresholdExceeded,
            int limit,
            long offset) {
        if (date != null && (from != null || to != null)) {
            throw new IllegalArgumentException("date cannot be combined with from or to");
        }

        Instant resolvedFrom;
        Instant resolvedTo;
        if (date != null) {
            resolvedFrom = date.atStartOfDay(applicationZone).toInstant();
            resolvedTo = date.plusDays(1).atStartOfDay(applicationZone).toInstant();
        } else {
            resolvedTo = to == null ? clock.instant() : to;
            resolvedFrom = from == null ? resolvedTo.minusSeconds(86_400) : from;
        }
        if (!resolvedFrom.isBefore(resolvedTo)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }
        if (Duration.between(resolvedFrom, resolvedTo).compareTo(properties.maxRange()) > 0) {
            throw new IllegalArgumentException(
                    "history range must not exceed " + properties.maxRange());
        }
        return new HistoryFilter(
                resolvedFrom,
                resolvedTo,
                groupId,
                taskId,
                thresholdExceeded,
                limit,
                offset);
    }
}
