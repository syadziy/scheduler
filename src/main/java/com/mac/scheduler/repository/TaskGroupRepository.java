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

    default List<TaskGroup> findAll() {
        return List.of();
    }

    default List<TaskGroup> findAll(int limit, int offset) {
        return findAll().stream().skip(offset).limit(limit).toList();
    }

    default long count() {
        return findAll().size();
    }
}
