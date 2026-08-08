package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.dto.CreateTaskGroupResponse;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.AuditEventPublisher;
import com.mac.scheduler.service.TaskGroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TaskGroupServiceImpl implements TaskGroupService {

    private final TaskGroupRepository groupRepository;
    private final TaskRepository taskRepository;
    private final Clock clock;
    private final AuditEventPublisher auditEventPublisher;

    public TaskGroupServiceImpl(
            TaskGroupRepository groupRepository,
            TaskRepository taskRepository,
            Clock clock,
            AuditEventPublisher auditEventPublisher) {
        this.groupRepository = groupRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public CreateTaskGroupResponse create(CreateTaskGroupRequest request) {
        List<UUID> taskIds = immutableOrEmpty(request.taskIds());
        List<UUID> groupIds = immutableOrEmpty(request.groupIds());
        validateMemberIds(taskIds, groupIds);

        List<ScheduledTask> tasks = taskRepository.findByIds(taskIds);
        if (tasks.size() != taskIds.size()) {
            throw new IllegalArgumentException("taskIds: one or more tasks do not exist");
        }
        Map<UUID, ScheduledTask> taskById = tasks.stream()
                .collect(Collectors.toMap(ScheduledTask::id, Function.identity()));
        List<ScheduledTask> orderedTasks = taskIds.stream().map(taskById::get).toList();
        List<TaskGroup> childGroups = groupIds.stream()
                .map(groupId -> groupRepository.findById(groupId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "groupIds: one or more task groups do not exist")))
                .toList();
        validateHierarchy(taskIds, childGroups);

        Instant now = clock.instant();
        TaskGroup group = new TaskGroup(
                UUID.randomUUID(),
                request.name().trim(),
                request.executionMode(),
                request.enabled() == null || request.enabled(),
                orderedTasks,
                childGroups,
                now,
                now);
        groupRepository.insert(group, taskIds, groupIds);
        auditEventPublisher.publishCreated(
                "SCHEDULER_TASK_GROUP_CREATED", "SCHEDULER_TASK_GROUP", group.id());
        return new CreateTaskGroupResponse(
                group.id(),
                group.name(),
                group.executionMode(),
                taskIds,
                groupIds,
                group.createdAt());
    }

    private static List<UUID> immutableOrEmpty(List<UUID> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void validateMemberIds(List<UUID> taskIds, List<UUID> groupIds) {
        if (taskIds.isEmpty() && groupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "taskIds/groupIds: at least one task or task group is required");
        }
        if (taskIds.size() + groupIds.size() > 100) {
            throw new IllegalArgumentException(
                    "taskIds/groupIds: a task group cannot contain more than 100 direct members");
        }
        if (new LinkedHashSet<>(taskIds).size() != taskIds.size()) {
            throw new IllegalArgumentException("taskIds: duplicate task IDs are not allowed");
        }
        if (new LinkedHashSet<>(groupIds).size() != groupIds.size()) {
            throw new IllegalArgumentException("groupIds: duplicate task group IDs are not allowed");
        }
    }

    private static void validateHierarchy(List<UUID> directTaskIds, List<TaskGroup> childGroups) {
        Set<UUID> taskIds = new LinkedHashSet<>(directTaskIds);
        Set<UUID> groupIds = new LinkedHashSet<>();
        for (TaskGroup childGroup : childGroups) {
            validateHierarchy(childGroup, 2, taskIds, groupIds, new LinkedHashSet<>());
        }
    }

    private static void validateHierarchy(
            TaskGroup group,
            int depth,
            Set<UUID> taskIds,
            Set<UUID> groupIds,
            Set<UUID> path) {
        if (depth > TaskGroup.MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException(
                    "groupIds: nested task groups cannot exceed 5 levels");
        }
        if (!path.add(group.id())) {
            throw new IllegalArgumentException(
                    "groupIds: circular task group references are not allowed");
        }
        if (!groupIds.add(group.id())) {
            throw new IllegalArgumentException(
                    "groupIds: the same nested task group cannot appear more than once");
        }
        for (ScheduledTask task : group.tasks()) {
            if (!taskIds.add(task.id())) {
                throw new IllegalArgumentException(
                        "taskIds/groupIds: the same task cannot appear more than once in a hierarchy");
            }
        }
        for (TaskGroup childGroup : group.groups()) {
            validateHierarchy(childGroup, depth + 1, taskIds, groupIds, path);
        }
        path.remove(group.id());
    }
}
