package com.mac.scheduler.repository.impl;

import com.mac.scheduler.entities.constant.ExecutionStatus;
import com.mac.scheduler.entities.model.HistoryFilter;
import com.mac.scheduler.entities.model.TaskExecutionResult;
import com.mac.scheduler.repository.TaskHistoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskHistoryRepositoryImpl implements TaskHistoryRepository {

    private static final String FILTER_BASE = """
            FROM scheduler_task_history
            WHERE started_at >= :from
              AND started_at < :to
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TaskHistoryRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(TaskExecutionResult result) {
        jdbcTemplate.update("""
                INSERT INTO scheduler_task_history (
                    id, execution_id, schedule_id, group_id, task_id, task_name, status,
                    started_at, completed_at, duration_ms, threshold_ms, threshold_exceeded,
                    http_status_code, error_message
                ) VALUES (
                    :id, :executionId, :scheduleId, :groupId, :taskId, :taskName, :status,
                    :startedAt, :completedAt, :durationMs, :thresholdMs, :thresholdExceeded,
                    :httpStatusCode, :errorMessage
                )
                ON CONFLICT (execution_id, task_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", result.historyId())
                .addValue("executionId", result.executionId())
                .addValue("scheduleId", result.scheduleId())
                .addValue("groupId", result.groupId())
                .addValue("taskId", result.taskId())
                .addValue("taskName", result.taskName())
                .addValue("status", result.status().name())
                .addValue("startedAt", result.startedAt())
                .addValue("completedAt", result.completedAt())
                .addValue("durationMs", result.durationMs())
                .addValue("thresholdMs", result.thresholdMs())
                .addValue("thresholdExceeded", result.thresholdExceeded())
                .addValue("httpStatusCode", result.httpStatusCode())
                .addValue("errorMessage", result.errorMessage()));
    }

    @Override
    public List<TaskExecutionResult> find(HistoryFilter filter) {
        Query query = buildFilter(filter);
        String sql = """
                SELECT id, execution_id, schedule_id, group_id, task_id, task_name, status,
                       started_at, completed_at, duration_ms, threshold_ms, threshold_exceeded,
                       http_status_code, error_message
                """ + query.sql() + " ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset";
        query.parameters()
                .addValue("limit", filter.limit())
                .addValue("offset", filter.offset());
        return jdbcTemplate.query(sql, query.parameters(), this::mapResult);
    }

    @Override
    public long count(HistoryFilter filter) {
        Query query = buildFilter(filter);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + query.sql(),
                query.parameters(),
                Long.class);
        return count == null ? 0 : count;
    }

    private Query buildFilter(HistoryFilter filter) {
        StringBuilder sql = new StringBuilder(FILTER_BASE);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("from", filter.from())
                .addValue("to", filter.to());
        List<String> predicates = new ArrayList<>();
        if (filter.groupId() != null) {
            predicates.add("group_id = :groupId");
            parameters.addValue("groupId", filter.groupId());
        }
        if (filter.taskId() != null) {
            predicates.add("task_id = :taskId");
            parameters.addValue("taskId", filter.taskId());
        }
        if (filter.thresholdExceeded() != null) {
            predicates.add("threshold_exceeded = :thresholdExceeded");
            parameters.addValue("thresholdExceeded", filter.thresholdExceeded());
        }
        predicates.forEach(predicate -> sql.append(" AND ").append(predicate));
        return new Query(sql.toString(), parameters);
    }

    private TaskExecutionResult mapResult(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskExecutionResult(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getObject("execution_id", java.util.UUID.class),
                resultSet.getObject("schedule_id", java.util.UUID.class),
                resultSet.getObject("group_id", java.util.UUID.class),
                resultSet.getObject("task_id", java.util.UUID.class),
                resultSet.getString("task_name"),
                ExecutionStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getLong("duration_ms"),
                resultSet.getLong("threshold_ms"),
                resultSet.getBoolean("threshold_exceeded"),
                resultSet.getObject("http_status_code", Integer.class),
                resultSet.getString("error_message"));
    }

    private record Query(String sql, MapSqlParameterSource parameters) {}
}
