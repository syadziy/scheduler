package com.mac.scheduler.service.impl;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class ScheduleCalculator {

    public Instant nextExecution(String expression, ZoneId zoneId, Instant after) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(expression);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cronExpression: invalid Spring cron expression", exception);
        }
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, zoneId));
        if (next == null) {
            throw new IllegalArgumentException("cronExpression: no future execution can be calculated");
        }
        return next.toInstant();
    }
}
