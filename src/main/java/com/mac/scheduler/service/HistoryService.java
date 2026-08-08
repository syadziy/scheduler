package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.TaskHistoryResponse;
import com.mac.scheduler.entities.model.HistoryFilter;
import java.util.List;

public interface HistoryService {

    List<TaskHistoryResponse> find(HistoryFilter filter);

    long count(HistoryFilter filter);
}
