package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.scheduler.config.properties.SchedulerEngineProperties;
import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.repository.ScheduleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DueScheduleClaimServiceTest {

    @Test
    void materializesOccurrenceBeforeAdvancingAndClaimingExecution() {
        RecordingScheduleRepository repository = new RecordingScheduleRepository();
        DueScheduleClaimService service = new DueScheduleClaimService(
                repository,
                new ScheduleCalculator(),
                new SchedulerEngineProperties(
                        true,
                        Duration.ofSeconds(5),
                        Duration.ofHours(1),
                        20,
                        50),
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<ScheduledExecution> executions = service.claimReadyExecutions("worker-1");

        assertThat(executions).hasSize(1);
        assertThat(repository.events)
                .containsExactly("findDue", "enqueue:" + DUE_AT, "update", "claim");
        assertThat(repository.updatedNextExecution).isAfter(NOW);
    }

    private static final class RecordingScheduleRepository implements ScheduleRepository {

        private final List<String> events = new ArrayList<>();
        private Instant updatedNextExecution;
        private UUID queuedExecutionId;

        @Override
        public ScheduleDefinition insert(ScheduleDefinition schedule) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScheduleDefinition> findDueForUpdate(Instant dueAt, int limit) {
            events.add("findDue");
            return List.of(schedule());
        }

        @Override
        public void updateNextExecution(
                ScheduleDefinition schedule,
                Instant nextExecutionAt,
                Instant updatedAt) {
            events.add("update");
            updatedNextExecution = nextExecutionAt;
        }

        @Override
        public void enqueueExecution(
                UUID executionId,
                ScheduleDefinition schedule,
                Instant scheduledFor,
                Instant createdAt) {
            events.add("enqueue:" + scheduledFor);
            queuedExecutionId = executionId;
        }

        @Override
        public List<ScheduledExecution> claimPendingExecutions(
                Instant now,
                Instant staleBefore,
                int limit,
                String workerId) {
            events.add("claim");
            return List.of(new ScheduledExecution(queuedExecutionId, schedule(), DUE_AT));
        }

        @Override
        public void completeExecution(
                UUID executionId,
                String status,
                Instant completedAt,
                String errorMessage,
                String workerId) {
            throw new UnsupportedOperationException();
        }
    }

    private static ScheduleDefinition schedule() {
        return new ScheduleDefinition(
                UUID.fromString("c770c541-2494-473c-9b14-37eb54f7b8c3"),
                "Every minute",
                ScheduleTargetType.TASK,
                UUID.fromString("a0f86f91-8e10-45ac-8897-a7e1c0497570"),
                null,
                "0 * * * * *",
                ZoneId.of("UTC"),
                true,
                DUE_AT,
                NOW.minusSeconds(600),
                NOW.minusSeconds(600));
    }

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:30Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-09T00:00:00Z");
}
