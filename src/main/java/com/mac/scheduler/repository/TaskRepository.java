package com.mac.scheduler.repository;

import com.mac.scheduler.entities.model.ScheduledTask;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {

    ScheduledTask insert(ScheduledTask task);

    Optional<ScheduledTask> findById(UUID taskId);

    List<ScheduledTask> findByIds(Collection<UUID> taskIds);
}
