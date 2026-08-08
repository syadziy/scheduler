package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateTaskRequest;
import com.mac.scheduler.entities.dto.CreateTaskResponse;

public interface TaskService {

    CreateTaskResponse create(CreateTaskRequest request);
}
