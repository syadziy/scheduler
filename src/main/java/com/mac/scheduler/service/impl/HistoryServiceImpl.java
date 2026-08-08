package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.dto.TaskHistoryResponse;
import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.repository.TaskHistoryRepository;
import com.mac.scheduler.service.HistoryService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HistoryServiceImpl implements HistoryService {

    private final TaskHistoryRepository repository;

    public HistoryServiceImpl(TaskHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<TaskHistoryResponse> find(HistoryFilter filter) {
        return repository.find(filter).stream().map(HistoryServiceImpl::toResponse).toList();
    }

    @Override
    public long count(HistoryFilter filter) {
        return repository.count(filter);
    }

    private static TaskHistoryResponse toResponse(TaskExecutionResult result) {
        return new TaskHistoryResponse(
                result.historyId(),
                result.executionId(),
                result.scheduleId(),
                result.groupId(),
                result.taskId(),
                result.taskName(),
                result.status(),
                result.startedAt(),
                result.completedAt(),
                result.durationMs(),
                result.thresholdMs(),
                result.thresholdExceeded(),
                result.httpStatusCode(),
                result.errorMessage());
    }
}
