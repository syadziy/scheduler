package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateScheduleRequest;
import com.mac.scheduler.entities.dto.CreateScheduleResponse;

public interface ScheduleService {

    CreateScheduleResponse create(CreateScheduleRequest request);
}
