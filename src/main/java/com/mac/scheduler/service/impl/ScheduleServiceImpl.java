package com.mac.scheduler.service.impl;

import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.dto.CreateScheduleRequest;
import com.mac.scheduler.entities.dto.CreateScheduleResponse;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.repository.ScheduleRepository;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.service.AuditEventPublisher;
import com.mac.scheduler.service.ScheduleService;
import com.mac.sdk_util.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ScheduleServiceImpl implements ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final TaskRepository taskRepository;
    private final TaskGroupRepository groupRepository;
    private final ScheduleCalculator calculator;
    private final Clock clock;
    private final AuditEventPublisher auditEventPublisher;

    public ScheduleServiceImpl(
            ScheduleRepository scheduleRepository,
            TaskRepository taskRepository,
            TaskGroupRepository groupRepository,
            ScheduleCalculator calculator,
            Clock clock,
            AuditEventPublisher auditEventPublisher) {
        this.scheduleRepository = scheduleRepository;
        this.taskRepository = taskRepository;
        this.groupRepository = groupRepository;
        this.calculator = calculator;
        this.clock = clock;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public CreateScheduleResponse create(CreateScheduleRequest request) {
        validateTarget(request);
        ZoneId zoneId = parseZoneId(request.zoneId());
        Instant now = clock.instant();
        Instant nextExecutionAt = calculator.nextExecution(
                request.cronExpression().trim(), zoneId, now);
        boolean enabled = request.enabled() == null || request.enabled();

        ScheduleDefinition schedule = new ScheduleDefinition(
                UUID.randomUUID(),
                request.name().trim(),
                request.targetType(),
                request.taskId(),
                request.groupId(),
                request.cronExpression().trim(),
                zoneId,
                enabled,
                nextExecutionAt,
                now,
                now);
        scheduleRepository.insert(schedule);
        auditEventPublisher.publishCreated(
                "SCHEDULER_SCHEDULE_CREATED", "SCHEDULER_SCHEDULE", schedule.id());
        UUID targetId = schedule.targetType() == ScheduleTargetType.TASK
                ? schedule.taskId()
                : schedule.groupId();
        return new CreateScheduleResponse(
                schedule.id(),
                schedule.name(),
                schedule.targetType(),
                targetId,
                schedule.nextExecutionAt(),
                schedule.createdAt());
    }

    private void validateTarget(CreateScheduleRequest request) {
        if (request.targetType() == ScheduleTargetType.TASK) {
            if (request.taskId() == null || request.groupId() != null) {
                throw new IllegalArgumentException(
                        "TASK schedule requires taskId and does not allow groupId");
            }
            taskRepository.findById(request.taskId())
                    .orElseThrow(() -> new ResourceNotFoundException("Task was not found"));
            return;
        }
        if (request.groupId() == null || request.taskId() != null) {
            throw new IllegalArgumentException(
                    "GROUP schedule requires groupId and does not allow taskId");
        }
        groupRepository.findById(request.groupId())
                .orElseThrow(() -> new ResourceNotFoundException("Task group was not found"));
    }

    private static ZoneId parseZoneId(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("zoneId: invalid time zone", exception);
        }
    }
}
