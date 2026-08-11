package com.mac.scheduler.repository.impl;

import com.mac.scheduler.entities.constant.ScheduleTargetType;
import com.mac.scheduler.entities.model.ScheduleDefinition;
import com.mac.scheduler.entities.model.ScheduledExecution;
import com.mac.scheduler.repository.ScheduleRepository;
import com.mac.scheduler.utils.exception.SchedulerConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepositoryImpl implements ScheduleRepository {

    private static final String COLUMNS = """
            id, name, target_type, task_id, group_id, cron_expression, zone_id,
            enabled, next_execution_at, created_at, updated_at
            """;
    private static final String JOINED_COLUMNS = """
            s.id, s.name, s.target_type, s.task_id, s.group_id, s.cron_expression, s.zone_id,
            s.enabled, s.next_execution_at, s.created_at, s.updated_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ScheduleRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ScheduleDefinition insert(ScheduleDefinition schedule) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO scheduler_schedule (
                        id, name, target_type, task_id, group_id, cron_expression, zone_id,
                        enabled, next_execution_at, created_at, updated_at
                    ) VALUES (
                        :id, :name, :targetType, :taskId, :groupId, :cronExpression, :zoneId,
                        :enabled, :nextExecutionAt, :createdAt, :updatedAt
                    )
                    """, parameters(schedule)
                    .addValue("nextExecutionAt", timestamp(schedule.nextExecutionAt())));
            return schedule;
        } catch (DuplicateKeyException exception) {
            throw new SchedulerConflictException("A schedule with the same name already exists", exception);
        }
    }

    @Override
    public List<ScheduleDefinition> findAll() {
        return jdbcTemplate.query("""
                SELECT %s FROM scheduler_schedule ORDER BY created_at DESC, id
                """.formatted(COLUMNS), this::mapSchedule);
    }

    @Override
    public List<ScheduleDefinition> findDueForUpdate(Instant dueAt, int limit) {
        return jdbcTemplate.query("""
                SELECT %s
                FROM scheduler_schedule
                WHERE enabled = true
                  AND next_execution_at <= :dueAt
                ORDER BY next_execution_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
                """.formatted(COLUMNS),
                new MapSqlParameterSource()
                        .addValue("dueAt", timestamp(dueAt))
                        .addValue("limit", limit),
                this::mapSchedule);
    }

    @Override
    public void updateNextExecution(
            ScheduleDefinition schedule,
            Instant nextExecutionAt,
            Instant updatedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE scheduler_schedule
                SET next_execution_at = :nextExecutionAt,
                    updated_at = :updatedAt
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", schedule.id())
                .addValue("nextExecutionAt", timestamp(nextExecutionAt))
                .addValue("updatedAt", timestamp(updatedAt)));
        if (updated != 1) {
            throw new IllegalStateException("Due schedule could not be claimed: " + schedule.id());
        }
    }

    @Override
    public void enqueueExecution(
            UUID executionId,
            ScheduleDefinition schedule,
            Instant scheduledFor,
            Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO scheduler_execution (
                    id, schedule_id, scheduled_for, status, created_at
                ) VALUES (:id, :scheduleId, :scheduledFor, 'PENDING', :createdAt)
                ON CONFLICT (schedule_id, scheduled_for) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", executionId)
                .addValue("scheduleId", schedule.id())
                .addValue("scheduledFor", timestamp(scheduledFor))
                .addValue("createdAt", timestamp(createdAt)));
    }

    @Override
    public List<ScheduledExecution> claimPendingExecutions(
            Instant now,
            Instant staleBefore,
            int limit,
            String workerId) {
        List<ScheduledExecution> executions = jdbcTemplate.query("""
                SELECT e.id AS execution_id, e.scheduled_for, %s
                FROM scheduler_execution e
                JOIN scheduler_schedule s ON s.id = e.schedule_id
                WHERE e.status = 'PENDING'
                   OR (e.status = 'RUNNING' AND e.started_at < :staleBefore)
                ORDER BY e.scheduled_for, e.id
                FOR UPDATE OF e SKIP LOCKED
                LIMIT :limit
                """.formatted(JOINED_COLUMNS),
                new MapSqlParameterSource()
                        .addValue("staleBefore", timestamp(staleBefore))
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new ScheduledExecution(
                        resultSet.getObject("execution_id", UUID.class),
                        mapSchedule(resultSet, rowNumber),
                        resultSet.getTimestamp("scheduled_for").toInstant()));
        for (ScheduledExecution execution : executions) {
            jdbcTemplate.update("""
                    UPDATE scheduler_execution
                    SET status = 'RUNNING',
                        worker_id = :workerId,
                        started_at = :startedAt,
                        completed_at = NULL,
                        error_message = NULL
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", execution.id())
                    .addValue("workerId", workerId)
                    .addValue("startedAt", timestamp(now)));
        }
        return List.copyOf(executions);
    }

    @Override
    public void completeExecution(
            UUID executionId,
            String status,
            Instant completedAt,
            String errorMessage,
            String workerId) {
        int updated = jdbcTemplate.update("""
                UPDATE scheduler_execution
                SET status = :status,
                    completed_at = :completedAt,
                    error_message = :errorMessage
                WHERE id = :id
                  AND status = 'RUNNING'
                  AND worker_id = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", executionId)
                .addValue("status", status)
                .addValue("completedAt", timestamp(completedAt))
                .addValue("errorMessage", errorMessage)
                .addValue("workerId", workerId));
        if (updated != 1) {
            throw new IllegalStateException("Schedule execution state could not be completed: " + executionId);
        }
    }

    private MapSqlParameterSource parameters(ScheduleDefinition schedule) {
        return new MapSqlParameterSource()
                .addValue("id", schedule.id())
                .addValue("name", schedule.name())
                .addValue("targetType", schedule.targetType().name())
                .addValue("taskId", schedule.taskId())
                .addValue("groupId", schedule.groupId())
                .addValue("cronExpression", schedule.cronExpression())
                .addValue("zoneId", schedule.zoneId().getId())
                .addValue("enabled", schedule.enabled())
                .addValue("createdAt", timestamp(schedule.createdAt()))
                .addValue("updatedAt", timestamp(schedule.updatedAt()));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private ScheduleDefinition mapSchedule(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ScheduleDefinition(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getString("name"),
                ScheduleTargetType.valueOf(resultSet.getString("target_type")),
                resultSet.getObject("task_id", java.util.UUID.class),
                resultSet.getObject("group_id", java.util.UUID.class),
                resultSet.getString("cron_expression"),
                ZoneId.of(resultSet.getString("zone_id")),
                resultSet.getBoolean("enabled"),
                resultSet.getTimestamp("next_execution_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
