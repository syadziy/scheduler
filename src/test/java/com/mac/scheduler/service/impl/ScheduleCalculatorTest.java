package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTest {

    private final ScheduleCalculator calculator = new ScheduleCalculator();

    @Test
    void calculatesNextExecutionInConfiguredTimeZone() {
        Instant next = calculator.nextExecution(
                "0 0 9 * * *",
                ZoneId.of("Asia/Jakarta"),
                Instant.parse("2026-08-09T00:30:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-08-09T02:00:00Z"));
    }

    @Test
    void rejectsInvalidCronExpression() {
        assertThatThrownBy(() -> calculator.nextExecution(
                        "invalid",
                        ZoneId.of("UTC"),
                        Instant.parse("2026-08-09T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cronExpression: invalid Spring cron expression");
    }
}
