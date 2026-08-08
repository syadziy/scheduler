package com.mac.scheduler.repository.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.repository.TaskRepository;
import com.mac.scheduler.utils.exception.SchedulerConflictException;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private static final String BASE_SELECT = """
            SELECT id, name, http_method, endpoint, headers, request_body,
                   timeout_ms, threshold_ms, enabled, created_at, updated_at
            FROM scheduler_task
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ScheduledTask> rowMapper = this::mapTask;

    public TaskRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScheduledTask insert(ScheduledTask task) {
        String sql = """
                INSERT INTO scheduler_task (
                    id, name, http_method, endpoint, headers, request_body,
                    timeout_ms, threshold_ms, enabled, created_at, updated_at
                ) VALUES (
                    :id, :name, :httpMethod, :endpoint, CAST(:headers AS jsonb), :requestBody,
                    :timeoutMs, :thresholdMs, :enabled, :createdAt, :updatedAt
                )
                """;
        try {
            jdbcTemplate.update(sql, new MapSqlParameterSource()
                    .addValue("id", task.id())
                    .addValue("name", task.name())
                    .addValue("httpMethod", task.method().name())
                    .addValue("endpoint", task.endpoint().toString())
                    .addValue("headers", writeHeaders(task.headers()))
                    .addValue("requestBody", task.requestBody())
                    .addValue("timeoutMs", task.timeout().toMillis())
                    .addValue("thresholdMs", task.threshold().toMillis())
                    .addValue("enabled", task.enabled())
                    .addValue("createdAt", task.createdAt())
                    .addValue("updatedAt", task.updatedAt()));
            return task;
        } catch (DuplicateKeyException exception) {
            throw new SchedulerConflictException("A task with the same name already exists", exception);
        }
    }

    @Override
    public Optional<ScheduledTask> findById(UUID taskId) {
        List<ScheduledTask> tasks = jdbcTemplate.query(
                BASE_SELECT + " WHERE id = :id",
                Map.of("id", taskId),
                rowMapper);
        return tasks.stream().findFirst();
    }

    @Override
    public List<ScheduledTask> findByIds(Collection<UUID> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE id IN (:ids)",
                Map.of("ids", taskIds),
                rowMapper);
    }

    private ScheduledTask mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ScheduledTask(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                HttpMethod.valueOf(resultSet.getString("http_method")),
                URI.create(resultSet.getString("endpoint")),
                readHeaders(resultSet.getString("headers")),
                resultSet.getString("request_body"),
                Duration.ofMillis(resultSet.getLong("timeout_ms")),
                Duration.ofMillis(resultSet.getLong("threshold_ms")),
                resultSet.getBoolean("enabled"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private String writeHeaders(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Task headers cannot be serialized", exception);
        }
    }

    private Map<String, String> readHeaders(String headers) {
        try {
            return objectMapper.readValue(headers, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored task headers cannot be read", exception);
        }
    }
}
