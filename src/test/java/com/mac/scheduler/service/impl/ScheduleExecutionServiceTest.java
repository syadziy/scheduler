package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.entities.constant.GroupExecutionMode;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.TaskExecutor;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScheduleExecutionServiceTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void executesSerialGroupOneTaskAtATimeInConfiguredOrder() {
        List<ScheduledTask> tasks = List.of(task("first"), task("second"), task("third"));
        RecordingTaskExecutor taskExecutor = new RecordingTaskExecutor();
        ScheduleExecutionService service = serviceFor(
                new TaskGroup(
                        GROUP_ID,
                        "Serial group",
                        GroupExecutionMode.SERIAL,
                        true,
                        tasks,
                        List.of(),
                        NOW,
                        NOW),
                taskExecutor);

        ExecutionStatus status = service.execute(execution());

        assertThat(status).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(taskExecutor.names).containsExactly("first", "second", "third");
        assertThat(taskExecutor.maxActive.get()).isEqualTo(1);
    }

    @Test
    void executesParallelGroupConcurrently() {
        List<ScheduledTask> tasks = List.of(task("first"), task("second"), task("third"));
        RecordingTaskExecutor taskExecutor = new RecordingTaskExecutor();
        ScheduleExecutionService service = serviceFor(
                new TaskGroup(
                        GROUP_ID,
                        "Parallel group",
                        GroupExecutionMode.PARALLEL,
                        true,
                        tasks,
                        List.of(),
                        NOW,
                        NOW),
                taskExecutor);

        ExecutionStatus status = service.execute(execution());

        assertThat(status).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(taskExecutor.names).containsExactlyInAnyOrder("first", "second", "third");
        assertThat(taskExecutor.maxActive.get()).isGreaterThan(1);
    }

    @Test
    void executesNestedGroupUsingEachGroupsExecutionMode() {
        TaskGroup child = new TaskGroup(
                CHILD_GROUP_ID,
                "Parallel child",
                GroupExecutionMode.PARALLEL,
                true,
                List.of(task("child-first"), task("child-second")),
                List.of(),
                NOW,
                NOW);
        TaskGroup root = new TaskGroup(
                GROUP_ID,
                "Serial root",
                GroupExecutionMode.SERIAL,
                true,
                List.of(task("root")),
                List.of(child),
                NOW,
                NOW);
        RecordingTaskExecutor taskExecutor = new RecordingTaskExecutor();

        ExecutionStatus status = serviceFor(root, taskExecutor).execute(execution());

        assertThat(status).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(taskExecutor.names.getFirst()).isEqualTo("root");
        assertThat(taskExecutor.names).containsExactlyInAnyOrder("root", "child-first", "child-second");
        assertThat(taskExecutor.groupIds.get("root")).isEqualTo(GROUP_ID);
        assertThat(taskExecutor.groupIds.get("child-first")).isEqualTo(CHILD_GROUP_ID);
        assertThat(taskExecutor.groupIds.get("child-second")).isEqualTo(CHILD_GROUP_ID);
        assertThat(taskExecutor.maxActive.get()).isGreaterThan(1);
    }

    private ScheduleExecutionService serviceFor(TaskGroup group, TaskExecutor taskExecutor) {
        TaskRepository taskRepository = new TaskRepository() {
            @Override
            public ScheduledTask insert(ScheduledTask task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ScheduledTask> findById(UUID taskId) {
                return Optional.empty();
            }

            @Override
            public List<ScheduledTask> findByIds(Collection<UUID> taskIds) {
                return List.of();
            }
        };
        TaskGroupRepository groupRepository = new TaskGroupRepository() {
            @Override
            public TaskGroup insert(
                    TaskGroup value,
                    List<UUID> taskIds,
                    List<UUID> groupIds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<TaskGroup> findById(UUID groupId) {
                return Optional.of(group);
            }
        };
        return new ScheduleExecutionService(
                taskRepository,
                groupRepository,
                taskExecutor,
                executor);
    }

    private static ScheduledExecution execution() {
        ScheduleDefinition schedule = new ScheduleDefinition(
                SCHEDULE_ID,
                "Group schedule",
                ScheduleTargetType.GROUP,
                null,
                GROUP_ID,
                "0 * * * * *",
                ZoneId.of("UTC"),
                true,
                NOW,
                NOW,
                NOW);
        return new ScheduledExecution(EXECUTION_ID, schedule, NOW);
    }

    private static ScheduledTask task(String name) {
        return new ScheduledTask(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name,
                HttpMethod.GET,
                URI.create("https://example.test/" + name),
                Map.of(),
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                true,
                NOW,
                NOW);
    }

    private static final class RecordingTaskExecutor implements TaskExecutor {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final List<String> names = new CopyOnWriteArrayList<>();
        private final Map<String, UUID> groupIds = new ConcurrentHashMap<>();

        @Override
        public TaskExecutionResult execute(
                ScheduledTask task,
                ScheduleDefinition schedule,
                UUID executionId,
                UUID groupId) {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            names.add(task.name());
            groupIds.put(task.name(), groupId);
            try {
                Thread.sleep(Duration.ofMillis(100));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
            return new TaskExecutionResult(
                    UUID.randomUUID(),
                    executionId,
                    schedule.id(),
                    groupId,
                    task.id(),
                    task.name(),
                    ExecutionStatus.SUCCESS,
                    NOW,
                    NOW.plusMillis(100),
                    100,
                    1_000,
                    false,
                    200,
                    null);
        }
    }

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final UUID GROUP_ID = UUID.fromString("dd58240d-cfaf-44e0-b4ab-fc10f9389f95");
    private static final UUID CHILD_GROUP_ID = UUID.fromString("c472a8d4-0ac2-431a-a597-169299c14933");
    private static final UUID SCHEDULE_ID = UUID.fromString("2f56cbab-cf10-48a0-909d-a8d74452822f");
    private static final UUID EXECUTION_ID = UUID.fromString("66cbde86-84fc-4ad8-bcb8-411696f7fd62");
}
