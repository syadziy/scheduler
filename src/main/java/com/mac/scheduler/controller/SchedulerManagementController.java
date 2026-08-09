package com.mac.scheduler.controller;

import com.mac.scheduler.entities.dto.CreateScheduleRequest;
import com.mac.scheduler.entities.dto.CreateScheduleResponse;
import com.mac.scheduler.entities.dto.CreateTaskGroupRequest;
import com.mac.scheduler.entities.dto.CreateTaskGroupResponse;
import com.mac.scheduler.entities.dto.CreateTaskRequest;
import com.mac.scheduler.entities.dto.CreateTaskResponse;
import com.mac.scheduler.service.ScheduleService;
import com.mac.scheduler.service.TaskGroupService;
import com.mac.scheduler.service.TaskService;
import com.mac.sdk_util.entities.dto.ResponseDTO;
import com.mac.sdk_util.utils.ResponseHelper;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SchedulerManagementController {

    private final TaskService taskService;
    private final TaskGroupService groupService;
    private final ScheduleService scheduleService;

    public SchedulerManagementController(
            TaskService taskService,
            TaskGroupService groupService,
            ScheduleService scheduleService) {
        this.taskService = taskService;
        this.groupService = groupService;
        this.scheduleService = scheduleService;
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('PERM_scheduler:manage')")
    public ResponseEntity<ResponseDTO<CreateTaskResponse>> createTask(
            @Valid @RequestBody CreateTaskRequest request) {
        CreateTaskResponse response = taskService.create(request);
        return ResponseHelper.httpCreated(
                response,
                URI.create("/api/v1/tasks/" + response.taskId()));
    }

    @PostMapping("/task-groups")
    @PreAuthorize("hasAuthority('PERM_scheduler:manage')")
    public ResponseEntity<ResponseDTO<CreateTaskGroupResponse>> createTaskGroup(
            @Valid @RequestBody CreateTaskGroupRequest request) {
        CreateTaskGroupResponse response = groupService.create(request);
        return ResponseHelper.httpCreated(
                response,
                URI.create("/api/v1/task-groups/" + response.groupId()));
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasAuthority('PERM_scheduler:manage')")
    public ResponseEntity<ResponseDTO<CreateScheduleResponse>> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request) {
        CreateScheduleResponse response = scheduleService.create(request);
        return ResponseHelper.httpCreated(
                response,
                URI.create("/api/v1/schedules/" + response.scheduleId()));
    }
}
