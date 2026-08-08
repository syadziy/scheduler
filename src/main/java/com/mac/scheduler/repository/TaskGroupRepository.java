package com.mac.scheduler.repository;

import com.mac.scheduler.entities.model.TaskGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskGroupRepository {

    TaskGroup insert(
            TaskGroup group,
            List<UUID> orderedTaskIds,
            List<UUID> orderedChildGroupIds);

    Optional<TaskGroup> findById(UUID groupId);
}
