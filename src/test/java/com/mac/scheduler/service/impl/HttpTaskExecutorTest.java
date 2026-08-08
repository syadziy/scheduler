package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.model.ErrorAlert;
import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.repository.TaskHistoryRepository;
import com.mac.scheduler.service.HttpTransport;
import com.mac.scheduler.service.ErrorAlertNotifier;
import com.mac.scheduler.service.ThresholdAlertNotifier;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;

class HttpTaskExecutorTest {

    @Test
    void persistsHistoryAndNotifiesWhenThresholdIsExceeded() {
        InMemoryHistoryRepository history = new InMemoryHistoryRepository();
        RecordingNotifier notifier = new RecordingNotifier();
        ErrorAlertNotifier errorNotifier = alert -> {};
        HttpTransport transport = request -> {
            Thread.sleep(Duration.ofMillis(30));
            return 204;
        };
        HttpTaskExecutor executor = new HttpTaskExecutor(
                transport,
                history,
                notifier,
                errorNotifier,
                new Semaphore(1),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskExecutionResult result = executor.execute(
                task(Duration.ofMillis(1)),
                schedule(),
                EXECUTION_ID,
                null);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.httpStatusCode()).isEqualTo(204);
        assertThat(result.thresholdExceeded()).isTrue();
        assertThat(history.results).containsExactly(result);
        assertThat(notifier.results).containsExactly(result);
    }

    @Test
    void sendsCentralizedErrorAlertWhenTaskFails() {
        InMemoryHistoryRepository history = new InMemoryHistoryRepository();
        RecordingErrorNotifier errorNotifier = new RecordingErrorNotifier();
        HttpTaskExecutor executor = new HttpTaskExecutor(
                request -> 503,
                history,
                result -> {},
                errorNotifier,
                new Semaphore(1),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskExecutionResult result = executor.execute(
                task(Duration.ofSeconds(10)), schedule(), EXECUTION_ID, null);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(errorNotifier.alerts).hasSize(1);
        assertThat(errorNotifier.alerts.getFirst().correlationId()).isEqualTo(EXECUTION_ID.toString());
    }

    private ScheduledTask task(Duration threshold) {
        return new ScheduledTask(
                TASK_ID,
                "HTTP task",
                HttpMethod.POST,
                URI.create("http://orders.internal/task"),
                Map.of("Content-Type", "application/json"),
                "{}",
                Duration.ofSeconds(2),
                threshold,
                true,
                NOW,
                NOW);
    }

    private static ScheduleDefinition schedule() {
        return new ScheduleDefinition(
                SCHEDULE_ID,
                "HTTP schedule",
                ScheduleTargetType.TASK,
                TASK_ID,
                null,
                "0 * * * * *",
                ZoneId.of("UTC"),
                true,
                NOW,
                NOW,
                NOW);
    }

    private static final class InMemoryHistoryRepository implements TaskHistoryRepository {

        private final List<TaskExecutionResult> results = new ArrayList<>();

        @Override
        public void insert(TaskExecutionResult result) {
            results.add(result);
        }

        @Override
        public List<TaskExecutionResult> find(HistoryFilter filter) {
            return List.copyOf(results);
        }

        @Override
        public long count(HistoryFilter filter) {
            return results.size();
        }
    }

    private static final class RecordingNotifier implements ThresholdAlertNotifier {

        private final List<TaskExecutionResult> results = new ArrayList<>();

        @Override
        public void send(TaskExecutionResult result) {
            results.add(result);
        }
    }

    private static final class RecordingErrorNotifier implements ErrorAlertNotifier {

        private final List<ErrorAlert> alerts = new ArrayList<>();

        @Override
        public void send(ErrorAlert alert) {
            alerts.add(alert);
        }
    }

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("8b3ee946-9598-46d7-8cfb-cdefe032c2b1");
    private static final UUID SCHEDULE_ID = UUID.fromString("fcab2fc3-29c4-403c-af14-f4199d75fd2f");
    private static final UUID EXECUTION_ID = UUID.fromString("a179fc39-9088-4d87-b89f-e44529700206");
}
