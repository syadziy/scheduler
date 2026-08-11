package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.dto.CreateScheduleRequest;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.ScheduleRepository;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.sdk_util.exception.ResourceNotFoundException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("f4e913ee-ff9c-4143-bd4c-b073912d6034");

    private InMemoryScheduleRepository scheduleRepository;
    private Optional<ScheduledTask> existingTask;
    private ScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        scheduleRepository = new InMemoryScheduleRepository();
        existingTask = Optional.empty();
        TaskRepository taskRepository = new TaskRepository() {
            @Override
            public ScheduledTask insert(ScheduledTask task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ScheduledTask> findById(UUID taskId) {
                return existingTask.filter(task -> task.id().equals(taskId));
            }

            @Override
            public List<ScheduledTask> findByIds(Collection<UUID> taskIds) {
                return List.of();
            }
        };
        TaskGroupRepository groupRepository = new TaskGroupRepository() {
            @Override
            public TaskGroup insert(
                    TaskGroup group,
                    List<UUID> taskIds,
                    List<UUID> groupIds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<TaskGroup> findById(UUID groupId) {
                return Optional.empty();
            }
        };
        service = new ScheduleServiceImpl(
                scheduleRepository,
                taskRepository,
                groupRepository,
                new ScheduleCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsTaskScheduleAndCalculatesNextExecution() {
        existingTask = Optional.of(task());

        var response = service.create(new CreateScheduleRequest(
                "Daily refresh",
                ScheduleTargetType.TASK,
                TASK_ID,
                null,
                "0 0 9 * * *",
                "Asia/Jakarta",
                true));

        assertThat(response.targetId()).isEqualTo(TASK_ID);
        assertThat(response.nextExecutionAt()).isEqualTo(Instant.parse("2026-08-09T02:00:00Z"));
        assertThat(scheduleRepository.inserted).isNotNull();
    }

    @Test
    void rejectsMismatchedTargetFields() {
        assertThatThrownBy(() -> service.create(new CreateScheduleRequest(
                        "Invalid",
                        ScheduleTargetType.TASK,
                        TASK_ID,
                        UUID.randomUUID(),
                        "0 0 9 * * *",
                        "UTC",
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TASK schedule requires taskId and does not allow groupId");
    }

    @Test
    void rejectsMissingTask() {
        assertThatThrownBy(() -> service.create(new CreateScheduleRequest(
                        "Missing task",
                        ScheduleTargetType.TASK,
                        TASK_ID,
                        null,
                        "0 0 9 * * *",
                        "UTC",
                        true)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Task was not found");
    }

    private static ScheduledTask task() {
        return new ScheduledTask(
                TASK_ID,
                "Task",
                HttpMethod.GET,
                URI.create("https://orders.internal"),
                Map.of(),
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                true,
                NOW,
                NOW);
    }

    private static final class InMemoryScheduleRepository implements ScheduleRepository {

        private ScheduleDefinition inserted;

        @Override
        public ScheduleDefinition insert(ScheduleDefinition schedule) {
            inserted = schedule;
            return schedule;
        }

        @Override
        public List<ScheduleDefinition> findDueForUpdate(Instant dueAt, int limit) {
            return List.of();
        }

        @Override
        public void updateNextExecution(
                ScheduleDefinition schedule,
                Instant nextExecutionAt,
                Instant updatedAt) {}

        @Override
        public void enqueueExecution(
                UUID executionId,
                ScheduleDefinition schedule,
                Instant scheduledFor,
                Instant createdAt) {}

        @Override
        public List<ScheduledExecution> claimPendingExecutions(
                Instant now,
                Instant staleBefore,
                int limit,
                String workerId) {
            return List.of();
        }

        @Override
        public void completeExecution(
                UUID executionId,
                String status,
                Instant completedAt,
                String errorMessage,
                String workerId) {}
    }
}
