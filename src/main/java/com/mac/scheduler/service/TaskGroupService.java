package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.dto.CreateTaskGroupResponse;

public interface TaskGroupService {

    CreateTaskGroupResponse create(CreateTaskGroupRequest request);
}
