package com.mac.scheduler.service;

import com.mac.scheduler.entities.model.ErrorAlert;

public interface ErrorAlertNotifier {

    void send(ErrorAlert alert);
}
