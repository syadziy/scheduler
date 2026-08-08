package com.mac.scheduler.repository;

import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import java.util.List;

public interface TaskHistoryRepository {

    void insert(TaskExecutionResult result);

    List<TaskExecutionResult> find(HistoryFilter filter);

    long count(HistoryFilter filter);
}
