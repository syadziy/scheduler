package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.dto.CreateTaskGroupResponse;
import com.mac.scheduler.entities.model.TaskGroup;
import java.util.List;

public interface TaskGroupService {

    CreateTaskGroupResponse create(CreateTaskGroupRequest request);

    List<TaskGroup> findAll();
}
