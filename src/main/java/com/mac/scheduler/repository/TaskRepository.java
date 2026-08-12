package com.mac.scheduler.repository;

import com.mac.scheduler.entities.model.ScheduledTask;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {

    ScheduledTask insert(ScheduledTask task);

    Optional<ScheduledTask> findById(UUID taskId);

    default List<ScheduledTask> findAll() {
        return List.of();
    }

    default List<ScheduledTask> findAll(int limit, int offset) {
        return findAll().stream().skip(offset).limit(limit).toList();
    }

    default long count() {
        return findAll().size();
    }

    List<ScheduledTask> findByIds(Collection<UUID> taskIds);
}
