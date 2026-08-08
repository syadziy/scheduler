package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mac.scheduler.config.properties.HttpTaskProperties;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.dto.CreateTaskRequest;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.AuditEventPublisher;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-09T01:00:00Z");

    private InMemoryTaskRepository repository;
    private TaskServiceImpl service;
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRepository();
        auditEventPublisher = mock(AuditEventPublisher.class);
        HttpTaskProperties properties = new HttpTaskProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(20),
                Duration.ofMinutes(10),
                Set.of("orders.internal"));
        service = new TaskServiceImpl(
                repository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                auditEventPublisher);
    }

    @Test
    void createsNormalizedTaskWithDefaultTimeout() {
        var response = service.create(new CreateTaskRequest(
                "  Refresh orders  ",
                HttpMethod.POST,
                URI.create("https://orders.internal/jobs/refresh"),
                Map.of("Content-Type", " application/json "),
                "{}",
                null,
                Duration.ofSeconds(5),
                null));

        assertThat(response.name()).isEqualTo("Refresh orders");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(repository.inserted.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(repository.inserted.enabled()).isTrue();
        assertThat(repository.inserted.headers()).containsEntry("Content-Type", "application/json");
        verify(auditEventPublisher).publishCreated(
                "SCHEDULER_TASK_CREATED", "SCHEDULER_TASK", response.taskId());
    }

    @Test
    void rejectsHostOutsideAllowList() {
        assertThatThrownBy(() -> service.create(new CreateTaskRequest(
                        "Unsafe task",
                        HttpMethod.GET,
                        URI.create("https://example.com/private"),
                        Map.of(),
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint: host is not in scheduler.http.allowed-hosts");
    }

    @Test
    void rejectsSchedulerManagedHeader() {
        assertThatThrownBy(() -> service.create(new CreateTaskRequest(
                        "Header task",
                        HttpMethod.GET,
                        URI.create("https://orders.internal/jobs"),
                        Map.of("X-Correlation-Id", "client-value"),
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headers: X-Correlation-Id is managed by the scheduler");
    }

    private static final class InMemoryTaskRepository implements TaskRepository {

        private ScheduledTask inserted;

        @Override
        public ScheduledTask insert(ScheduledTask task) {
            inserted = task;
            return task;
        }

        @Override
        public Optional<ScheduledTask> findById(UUID taskId) {
            return Optional.ofNullable(inserted).filter(task -> task.id().equals(taskId));
        }

        @Override
        public List<ScheduledTask> findByIds(Collection<UUID> taskIds) {
            return inserted == null || !taskIds.contains(inserted.id())
                    ? List.of()
                    : List.of(inserted);
        }
    }
}
