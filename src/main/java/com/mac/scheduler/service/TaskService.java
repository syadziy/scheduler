package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateTaskRequest;
import com.mac.scheduler.entities.dto.CreateTaskResponse;
import com.mac.scheduler.entities.model.ScheduledTask;
import java.util.List;

public interface TaskService {

    CreateTaskResponse create(CreateTaskRequest request);

    List<ScheduledTask> findAll(int limit, int offset);

    long count();
}
