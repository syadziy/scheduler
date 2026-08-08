package com.mac.scheduler.service;

import com.mac.scheduler.entities.model.TaskExecutionResult;

public interface ThresholdAlertNotifier {

    void send(TaskExecutionResult result);
}
