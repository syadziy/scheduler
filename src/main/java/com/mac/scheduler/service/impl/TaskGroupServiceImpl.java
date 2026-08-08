package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.dto.CreateTaskGroupResponse;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.TaskGroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TaskGroupServiceImpl implements TaskGroupService {

    private final TaskGroupRepository groupRepository;
    private final TaskRepository taskRepository;
    private final Clock clock;

    public TaskGroupServiceImpl(
            TaskGroupRepository groupRepository,
            TaskRepository taskRepository,
            Clock clock) {
        this.groupRepository = groupRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @Override
    public CreateTaskGroupResponse create(CreateTaskGroupRequest request) {
        List<UUID> taskIds = List.copyOf(request.taskIds());
        if (new LinkedHashSet<>(taskIds).size() != taskIds.size()) {
            throw new IllegalArgumentException("taskIds: duplicate task IDs are not allowed");
        }

        List<ScheduledTask> tasks = taskRepository.findByIds(taskIds);
        if (tasks.size() != taskIds.size()) {
            throw new IllegalArgumentException("taskIds: one or more tasks do not exist");
        }
        Map<UUID, ScheduledTask> taskById = tasks.stream()
                .collect(Collectors.toMap(ScheduledTask::id, Function.identity()));
        List<ScheduledTask> orderedTasks = taskIds.stream().map(taskById::get).toList();

        Instant now = clock.instant();
        TaskGroup group = new TaskGroup(
                UUID.randomUUID(),
                request.name().trim(),
                request.executionMode(),
                request.enabled() == null || request.enabled(),
                orderedTasks,
                now,
                now);
        groupRepository.insert(group, taskIds);
        return new CreateTaskGroupResponse(
                group.id(),
                group.name(),
                group.executionMode(),
                taskIds,
                group.createdAt());
    }
}
