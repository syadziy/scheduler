package com.mac.scheduler.repository.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mac.scheduler.entities.constant.*;
import com.mac.scheduler.entities.model.*;
import com.mac.scheduler.utils.exception.SchedulerConflictException;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class RepositoryCoverageTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void taskRepositoryCoversWritesReadsAndFailures() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TaskRepositoryImpl repository = new TaskRepositoryImpl(jdbc, mapper);
        ScheduledTask task = task(UUID.randomUUID());

        assertSame(task, repository.insert(task));
        verify(jdbc).update(contains("INSERT INTO scheduler_task"), any(MapSqlParameterSource.class));
        assertTrue(repository.findByIds(List.of()).isEmpty());

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<ScheduledTask> rowMapper = invocation.getArgument(2);
            return List.of(rowMapper.mapRow(taskResultSet(task), 0));
        });
        assertEquals(task, repository.findById(task.id()).orElseThrow());
        assertEquals(List.of(task), repository.findByIds(List.of(task.id())));

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThrows(SchedulerConflictException.class, () -> repository.insert(task));

        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JacksonException("bad") {});
        assertThrows(IllegalArgumentException.class,
                () -> new TaskRepositoryImpl(jdbc, broken).insert(task));

        when(broken.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new JacksonException("bad") {});
        TaskRepositoryImpl brokenReader = new TaskRepositoryImpl(jdbc, broken);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<ScheduledTask> rowMapper = invocation.getArgument(2);
            return List.of(rowMapper.mapRow(taskResultSet(task), 0));
        });
        assertThrows(IllegalStateException.class, () -> brokenReader.findById(task.id()));
    }

    @Test
    void historyRepositoryCoversFiltersMappingAndNullCount() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TaskHistoryRepositoryImpl repository = new TaskHistoryRepositoryImpl(jdbc);
        TaskExecutionResult result = result();
        repository.insert(result);

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<TaskExecutionResult> rowMapper = invocation.getArgument(2);
                    return List.of(rowMapper.mapRow(historyResultSet(result), 0));
                });
        HistoryFilter allFilters = new HistoryFilter(
                NOW.minusSeconds(60), NOW.plusSeconds(60), result.groupId(), result.taskId(), true, 25, 5);
        assertEquals(List.of(result), repository.find(allFilters));

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(7L, (Long) null);
        assertEquals(7, repository.count(allFilters));
        assertEquals(0, repository.count(new HistoryFilter(
                NOW.minusSeconds(60), NOW, null, null, null, 10, 0)));
    }

    @Test
    void scheduleRepositoryCoversLifecycleMappingAndConflicts() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ScheduleRepositoryImpl repository = new ScheduleRepositoryImpl(jdbc);
        ScheduleDefinition schedule = schedule();
        assertSame(schedule, repository.insert(schedule));
        repository.enqueueExecution(UUID.randomUUID(), schedule, NOW, NOW);

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(2);
                    ResultSet rs = scheduleResultSet(schedule);
                    if (invocation.<String>getArgument(0).contains("execution_id")) {
                        when(rs.getObject("execution_id", UUID.class)).thenReturn(UUID.randomUUID());
                        when(rs.getTimestamp("scheduled_for")).thenReturn(Timestamp.from(NOW));
                    }
                    return List.of(rowMapper.mapRow(rs, 0));
                });
        assertEquals(schedule, repository.findDueForUpdate(NOW, 5).getFirst());
        assertEquals(1, repository.claimPendingExecutions(NOW, NOW.minusSeconds(60), 5, "worker").size());

        when(jdbc.update(contains("SET next_execution_at"), any(MapSqlParameterSource.class)))
                .thenReturn(1, 0);
        repository.updateNextExecution(schedule, NOW.plusSeconds(60), NOW);
        assertThrows(IllegalStateException.class,
                () -> repository.updateNextExecution(schedule, NOW.plusSeconds(120), NOW));

        when(jdbc.update(contains("SET status = :status"), any(MapSqlParameterSource.class)))
                .thenReturn(1, 0);
        repository.completeExecution(UUID.randomUUID(), "SUCCESS", NOW, null, "worker");
        assertThrows(IllegalStateException.class,
                () -> repository.completeExecution(UUID.randomUUID(), "SUCCESS", NOW, null, "worker"));

        when(jdbc.update(startsWith("INSERT INTO scheduler_schedule"), any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThrows(SchedulerConflictException.class, () -> repository.insert(schedule));
    }

    @Test
    void taskGroupRepositoryCoversInsertNestedReadAndFailures() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TaskGroupRepositoryImpl repository = new TaskGroupRepositoryImpl(jdbc, mapper);
        UUID groupId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        ScheduledTask task = task(UUID.randomUUID());
        TaskGroup group = new TaskGroup(groupId, "root", GroupExecutionMode.SERIAL, true,
                List.of(), List.of(), NOW, NOW);
        assertSame(group, repository.insert(group, List.of(task.id()), List.of(childId)));
        verify(jdbc, times(3)).update(anyString(), any(MapSqlParameterSource.class));

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> rowMapper = invocation.getArgument(2);
            if (sql.contains("FROM scheduler_task_group")) {
                UUID requested = (UUID) ((Map<?, ?>) invocation.getArgument(1)).get("id");
                return List.of(rowMapper.mapRow(groupResultSet(requested,
                        requested.equals(groupId) ? "root" : "child"), 0));
            }
            if (sql.contains("FROM scheduler_group_group")) {
                UUID requested = (UUID) ((Map<?, ?>) invocation.getArgument(1)).get("parentGroupId");
                if (requested.equals(groupId)) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("child_group_id", UUID.class)).thenReturn(childId);
                    return List.of(rowMapper.mapRow(rs, 0));
                }
                return List.of();
            }
            return List.of(rowMapper.mapRow(taskResultSet(task), 0));
        });
        TaskGroup loaded = repository.findById(groupId).orElseThrow();
        assertEquals("child", loaded.groups().getFirst().name());
        assertEquals(task, loaded.tasks().getFirst());

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThrows(SchedulerConflictException.class,
                () -> repository.insert(group, List.of(), List.of()));

        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new JacksonException("bad") {});
        TaskGroupRepositoryImpl brokenRepository = new TaskGroupRepositoryImpl(jdbc, broken);
        reset(jdbc);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> rowMapper = invocation.getArgument(2);
            if (sql.contains("FROM scheduler_task_group")) {
                return List.of(rowMapper.mapRow(groupResultSet(groupId, "root"), 0));
            }
            if (sql.contains("FROM scheduler_group_group")) return List.of();
            return List.of(rowMapper.mapRow(taskResultSet(task), 0));
        });
        assertThrows(IllegalStateException.class, () -> brokenRepository.findById(groupId));
    }

    private static ScheduledTask task(UUID id) {
        return new ScheduledTask(id, "task", HttpMethod.POST, URI.create("https://example.com/run"),
                Map.of("X-Test", "yes"), "{}", Duration.ofSeconds(2), Duration.ofSeconds(1), true, NOW, NOW);
    }

    private static ScheduleDefinition schedule() {
        return new ScheduleDefinition(UUID.randomUUID(), "schedule", ScheduleTargetType.TASK,
                UUID.randomUUID(), null, "0 * * * * *", ZoneId.of("UTC"), true, NOW, NOW, NOW);
    }

    private static TaskExecutionResult result() {
        return new TaskExecutionResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "task", ExecutionStatus.SUCCESS,
                NOW.minusMillis(50), NOW, 50, 25, true, 200, null);
    }

    private static ResultSet taskResultSet(ScheduledTask task) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(task.id());
        when(rs.getString("name")).thenReturn(task.name());
        when(rs.getString("http_method")).thenReturn(task.method().name());
        when(rs.getString("endpoint")).thenReturn(task.endpoint().toString());
        when(rs.getString("headers")).thenReturn("{\"X-Test\":\"yes\"}");
        when(rs.getString("request_body")).thenReturn(task.requestBody());
        when(rs.getLong("timeout_ms")).thenReturn(task.timeout().toMillis());
        when(rs.getLong("threshold_ms")).thenReturn(task.threshold().toMillis());
        when(rs.getBoolean("enabled")).thenReturn(task.enabled());
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(task.createdAt()));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(task.updatedAt()));
        return rs;
    }

    private static ResultSet groupResultSet(UUID id, String name) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("execution_mode")).thenReturn(GroupExecutionMode.SERIAL.name());
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
        return rs;
    }

    private static ResultSet scheduleResultSet(ScheduleDefinition schedule) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(schedule.id());
        when(rs.getString("name")).thenReturn(schedule.name());
        when(rs.getString("target_type")).thenReturn(schedule.targetType().name());
        when(rs.getObject("task_id", UUID.class)).thenReturn(schedule.taskId());
        when(rs.getObject("group_id", UUID.class)).thenReturn(schedule.groupId());
        when(rs.getString("cron_expression")).thenReturn(schedule.cronExpression());
        when(rs.getString("zone_id")).thenReturn(schedule.zoneId().getId());
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getTimestamp("next_execution_at")).thenReturn(Timestamp.from(schedule.nextExecutionAt()));
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(schedule.createdAt()));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(schedule.updatedAt()));
        return rs;
    }

    private static ResultSet historyResultSet(TaskExecutionResult result) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(result.historyId());
        when(rs.getObject("execution_id", UUID.class)).thenReturn(result.executionId());
        when(rs.getObject("schedule_id", UUID.class)).thenReturn(result.scheduleId());
        when(rs.getObject("group_id", UUID.class)).thenReturn(result.groupId());
        when(rs.getObject("task_id", UUID.class)).thenReturn(result.taskId());
        when(rs.getString("task_name")).thenReturn(result.taskName());
        when(rs.getString("status")).thenReturn(result.status().name());
        when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(result.startedAt()));
        when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(result.completedAt()));
        when(rs.getLong("duration_ms")).thenReturn(result.durationMs());
        when(rs.getLong("threshold_ms")).thenReturn(result.thresholdMs());
        when(rs.getBoolean("threshold_exceeded")).thenReturn(result.thresholdExceeded());
        when(rs.getObject("http_status_code", Integer.class)).thenReturn(result.httpStatusCode());
        when(rs.getString("error_message")).thenReturn(result.errorMessage());
        return rs;
    }
}
