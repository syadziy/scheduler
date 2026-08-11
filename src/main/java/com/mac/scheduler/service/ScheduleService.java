package com.mac.scheduler.service;

import com.mac.scheduler.entities.dto.CreateScheduleRequest;
import com.mac.scheduler.entities.dto.CreateScheduleResponse;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import java.util.List;

public interface ScheduleService {

    CreateScheduleResponse create(CreateScheduleRequest request);

    List<ScheduleDefinition> findAll();
}
