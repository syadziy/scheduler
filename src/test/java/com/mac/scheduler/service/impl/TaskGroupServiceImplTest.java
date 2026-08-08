package com.mac.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.AuditEventPublisher;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskGroupServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    private InMemoryTaskGroupRepository groupRepository;
    private InMemoryTaskRepository taskRepository;
    private TaskGroupServiceImpl service;
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUp() {
        groupRepository = new InMemoryTaskGroupRepository();
        taskRepository = new InMemoryTaskRepository();
        auditEventPublisher = mock(AuditEventPublisher.class);
        service = new TaskGroupServiceImpl(
                groupRepository,
                taskRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                auditEventPublisher);
    }

    @Test
    void createsGroupWithFiveHierarchyLevels() {
        TaskGroup child = nestedGroup(4);
        groupRepository.existing = child;

        var response = service.create(new CreateTaskGroupRequest(
                "Root group",
                GroupExecutionMode.SERIAL,
                List.of(),
                List.of(child.id()),
                true));

        assertThat(response.groupIds()).containsExactly(child.id());
        assertThat(groupRepository.inserted.groups()).containsExactly(child);
        verify(auditEventPublisher).publishCreated(
                "SCHEDULER_TASK_GROUP_CREATED", "SCHEDULER_TASK_GROUP", response.groupId());
    }

    @Test
    void rejectsHierarchyDeeperThanFiveLevels() {
        TaskGroup child = nestedGroup(5);
        groupRepository.existing = child;

        assertThatThrownBy(() -> service.create(new CreateTaskGroupRequest(
                        "Too deep",
                        GroupExecutionMode.SERIAL,
                        List.of(),
                        List.of(child.id()),
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("groupIds: nested task groups cannot exceed 5 levels");
    }

    @Test
    void rejectsTaskRepeatedInDirectAndNestedMembers() {
        ScheduledTask repeatedTask = task("repeated");
        taskRepository.existing = repeatedTask;
        TaskGroup child = new TaskGroup(
                UUID.randomUUID(),
                "Child",
                GroupExecutionMode.SERIAL,
                true,
                List.of(repeatedTask),
                List.of(),
                NOW,
                NOW);
        groupRepository.existing = child;

        assertThatThrownBy(() -> service.create(new CreateTaskGroupRequest(
                        "Duplicate task hierarchy",
                        GroupExecutionMode.PARALLEL,
                        List.of(repeatedTask.id()),
                        List.of(child.id()),
                        true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskIds/groupIds: the same task cannot appear more than once in a hierarchy");
    }

    private static TaskGroup nestedGroup(int levels) {
        TaskGroup group = new TaskGroup(
                UUID.randomUUID(),
                "Level " + levels,
                GroupExecutionMode.SERIAL,
                true,
                List.of(task("leaf-" + levels)),
                List.of(),
                NOW,
                NOW);
        for (int level = levels - 1; level >= 1; level--) {
            group = new TaskGroup(
                    UUID.randomUUID(),
                    "Level " + level,
                    GroupExecutionMode.SERIAL,
                    true,
                    List.of(),
                    List.of(group),
                    NOW,
                    NOW);
        }
        return group;
    }

    private static ScheduledTask task(String name) {
        return new ScheduledTask(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name,
                HttpMethod.GET,
                URI.create("https://example.test/" + name),
                java.util.Map.of(),
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                true,
                NOW,
                NOW);
    }

    private static final class InMemoryTaskGroupRepository implements TaskGroupRepository {

        private TaskGroup existing;
        private TaskGroup inserted;

        @Override
        public TaskGroup insert(
                TaskGroup group,
                List<UUID> orderedTaskIds,
                List<UUID> orderedChildGroupIds) {
            inserted = group;
            return group;
        }

        @Override
        public Optional<TaskGroup> findById(UUID groupId) {
            return Optional.ofNullable(existing).filter(group -> group.id().equals(groupId));
        }
    }

    private static final class InMemoryTaskRepository implements TaskRepository {

        private ScheduledTask existing;

        @Override
        public ScheduledTask insert(ScheduledTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ScheduledTask> findById(UUID taskId) {
            return Optional.ofNullable(existing).filter(task -> task.id().equals(taskId));
        }

        @Override
        public List<ScheduledTask> findByIds(Collection<UUID> taskIds) {
            return existing != null && taskIds.contains(existing.id())
                    ? List.of(existing)
                    : List.of();
        }
    }
}
