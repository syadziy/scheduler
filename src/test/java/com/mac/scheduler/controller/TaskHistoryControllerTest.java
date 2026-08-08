package com.mac.scheduler.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mac.scheduler.entities.dto.TaskHistoryResponse;
import com.mac.scheduler.config.properties.HistoryProperties;
import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.service.HistoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskHistoryControllerTest {

    private RecordingHistoryService historyService;
    private TaskHistoryController controller;

    @BeforeEach
    void setUp() {
        historyService = new RecordingHistoryService();
        controller = new TaskHistoryController(
                historyService,
                ZoneId.of("Asia/Jakarta"),
                Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC),
                new HistoryProperties(java.time.Duration.ofDays(31)));
    }

    @Test
    void convertsSelectedDateToApplicationZoneRange() {
        var response = controller.findHistory(
                LocalDate.of(2026, 8, 9),
                null,
                null,
                null,
                null,
                true,
                50,
                0);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(historyService.filter.from()).isEqualTo(Instant.parse("2026-08-08T17:00:00Z"));
        assertThat(historyService.filter.to()).isEqualTo(Instant.parse("2026-08-09T17:00:00Z"));
        assertThat(historyService.filter.thresholdExceeded()).isTrue();
    }

    @Test
    void rejectsDateCombinedWithTimestampRange() {
        assertThatThrownBy(() -> controller.findHistory(
                        LocalDate.of(2026, 8, 9),
                        Instant.parse("2026-08-09T00:00:00Z"),
                        null,
                        null,
                        null,
                        null,
                        50,
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("date cannot be combined with from or to");
    }

    private static final class RecordingHistoryService implements HistoryService {

        private HistoryFilter filter;

        @Override
        public List<TaskHistoryResponse> find(HistoryFilter value) {
            filter = value;
            return List.of();
        }

        @Override
        public long count(HistoryFilter value) {
            return 0;
        }
    }
}
