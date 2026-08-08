package com.mac.scheduler.repository.impl;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import com.mac.scheduler.entities.constant.HttpMethod;
import com.mac.scheduler.entities.model.ScheduledTask;
import com.mac.scheduler.entities.model.TaskGroup;
import com.mac.scheduler.repository.TaskGroupRepository;
import com.mac.scheduler.utils.exception.SchedulerConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskGroupRepositoryImpl implements TaskGroupRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskGroupRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TaskGroup insert(
            TaskGroup group,
            List<UUID> orderedTaskIds,
            List<UUID> orderedChildGroupIds) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO scheduler_task_group (
                        id, name, execution_mode, enabled, created_at, updated_at
                    ) VALUES (:id, :name, :executionMode, :enabled, :createdAt, :updatedAt)
                    """, new MapSqlParameterSource()
                    .addValue("id", group.id())
                    .addValue("name", group.name())
                    .addValue("executionMode", group.executionMode().name())
                    .addValue("enabled", group.enabled())
                    .addValue("createdAt", group.createdAt())
                    .addValue("updatedAt", group.updatedAt()));

            for (int index = 0; index < orderedTaskIds.size(); index++) {
                jdbcTemplate.update("""
                        INSERT INTO scheduler_group_task (group_id, task_id, sequence_no)
                        VALUES (:groupId, :taskId, :sequenceNo)
                        """, new MapSqlParameterSource()
                        .addValue("groupId", group.id())
                        .addValue("taskId", orderedTaskIds.get(index))
                        .addValue("sequenceNo", index + 1));
            }
            for (int index = 0; index < orderedChildGroupIds.size(); index++) {
                jdbcTemplate.update("""
                        INSERT INTO scheduler_group_group (
                            parent_group_id, child_group_id, sequence_no
                        ) VALUES (:parentGroupId, :childGroupId, :sequenceNo)
                        """, new MapSqlParameterSource()
                        .addValue("parentGroupId", group.id())
                        .addValue("childGroupId", orderedChildGroupIds.get(index))
                        .addValue("sequenceNo", index + 1));
            }
            return group;
        } catch (DuplicateKeyException exception) {
            throw new SchedulerConflictException(
                    "A task group with the same name or duplicate task already exists",
                    exception);
        }
    }

    @Override
    public Optional<TaskGroup> findById(UUID groupId) {
        return findById(groupId, 1, new LinkedHashSet<>());
    }

    private Optional<TaskGroup> findById(
            UUID groupId,
            int depth,
            Set<UUID> path) {
        if (depth > TaskGroup.MAX_NESTING_DEPTH) {
            throw new IllegalStateException("Stored task group hierarchy exceeds 5 levels");
        }
        if (!path.add(groupId)) {
            throw new IllegalStateException("Stored task group hierarchy contains a cycle");
        }
        List<TaskGroup> groups = jdbcTemplate.query("""
                SELECT id, name, execution_mode, enabled, created_at, updated_at
                FROM scheduler_task_group
                WHERE id = :id
                """, Map.of("id", groupId), (resultSet, rowNumber) -> new TaskGroup(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                GroupExecutionMode.valueOf(resultSet.getString("execution_mode")),
                resultSet.getBoolean("enabled"),
                List.of(),
                List.of(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()));
        try {
            return groups.stream()
                    .findFirst()
                    .map(group -> new TaskGroup(
                            group.id(),
                            group.name(),
                            group.executionMode(),
                            group.enabled(),
                            findTasks(groupId),
                            findChildGroups(groupId, depth, path),
                            group.createdAt(),
                            group.updatedAt()));
        } finally {
            path.remove(groupId);
        }
    }

    private List<TaskGroup> findChildGroups(
            UUID parentGroupId,
            int parentDepth,
            Set<UUID> path) {
        List<UUID> childGroupIds = jdbcTemplate.query("""
                SELECT child_group_id
                FROM scheduler_group_group
                WHERE parent_group_id = :parentGroupId
                ORDER BY sequence_no
                """, Map.of("parentGroupId", parentGroupId),
                (resultSet, rowNumber) -> resultSet.getObject("child_group_id", UUID.class));
        return childGroupIds.stream()
                .map(childGroupId -> findById(childGroupId, parentDepth + 1, path)
                        .orElseThrow(() -> new IllegalStateException(
                                "Nested task group no longer exists: " + childGroupId)))
                .toList();
    }

    private List<ScheduledTask> findTasks(UUID groupId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.name, t.http_method, t.endpoint, t.headers, t.request_body,
                       t.timeout_ms, t.threshold_ms, t.enabled, t.created_at, t.updated_at
                FROM scheduler_group_task gt
                JOIN scheduler_task t ON t.id = gt.task_id
                WHERE gt.group_id = :groupId
                ORDER BY gt.sequence_no
                """, Map.of("groupId", groupId), this::mapTask);
    }

    private ScheduledTask mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            Map<String, String> headers = objectMapper.readValue(
                    resultSet.getString("headers"), new TypeReference<>() {});
            return new ScheduledTask(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("name"),
                    HttpMethod.valueOf(resultSet.getString("http_method")),
                    URI.create(resultSet.getString("endpoint")),
                    headers,
                    resultSet.getString("request_body"),
                    Duration.ofMillis(resultSet.getLong("timeout_ms")),
                    Duration.ofMillis(resultSet.getLong("threshold_ms")),
                    resultSet.getBoolean("enabled"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored task headers cannot be read", exception);
        }
    }
}
