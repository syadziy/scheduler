package com.mac.scheduler.service;

import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import java.util.UUID;

public interface TaskExecutor {

    TaskExecutionResult execute(
            ScheduledTask task,
            ScheduleDefinition schedule,
            UUID executionId,
            UUID groupId);
}
