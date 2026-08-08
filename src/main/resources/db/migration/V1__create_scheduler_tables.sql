CREATE TABLE scheduler_task (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    endpoint TEXT NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_body TEXT,
    timeout_ms BIGINT NOT NULL,
    threshold_ms BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_scheduler_task_http_method
        CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT ck_scheduler_task_timeout_positive CHECK (timeout_ms > 0),
    CONSTRAINT ck_scheduler_task_threshold_positive CHECK (threshold_ms > 0)
);

CREATE UNIQUE INDEX uk_scheduler_task_name_lower
    ON scheduler_task (LOWER(name));

CREATE TABLE scheduler_task_group (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    execution_mode VARCHAR(10) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_scheduler_group_execution_mode
        CHECK (execution_mode IN ('SERIAL', 'PARALLEL'))
);

CREATE UNIQUE INDEX uk_scheduler_task_group_name_lower
    ON scheduler_task_group (LOWER(name));

CREATE TABLE scheduler_group_task (
    group_id UUID NOT NULL REFERENCES scheduler_task_group(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES scheduler_task(id),
    sequence_no INTEGER NOT NULL,
    PRIMARY KEY (group_id, task_id),
    CONSTRAINT uk_scheduler_group_sequence UNIQUE (group_id, sequence_no),
    CONSTRAINT ck_scheduler_group_sequence_positive CHECK (sequence_no > 0)
);

CREATE INDEX idx_scheduler_group_task_task
    ON scheduler_group_task (task_id);

CREATE TABLE scheduler_schedule (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    target_type VARCHAR(10) NOT NULL,
    task_id UUID REFERENCES scheduler_task(id),
    group_id UUID REFERENCES scheduler_task_group(id),
    cron_expression VARCHAR(120) NOT NULL,
    zone_id VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_execution_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_scheduler_schedule_target_type
        CHECK (target_type IN ('TASK', 'GROUP')),
    CONSTRAINT ck_scheduler_schedule_target
        CHECK (
            (target_type = 'TASK' AND task_id IS NOT NULL AND group_id IS NULL)
            OR
            (target_type = 'GROUP' AND group_id IS NOT NULL AND task_id IS NULL)
        )
);

CREATE UNIQUE INDEX uk_scheduler_schedule_name_lower
    ON scheduler_schedule (LOWER(name));

CREATE INDEX idx_scheduler_schedule_due
    ON scheduler_schedule (next_execution_at, id)
    WHERE enabled = TRUE;

CREATE TABLE scheduler_execution (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES scheduler_schedule(id),
    scheduled_for TIMESTAMPTZ NOT NULL,
    status VARCHAR(10) NOT NULL,
    worker_id VARCHAR(300),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_scheduler_execution_occurrence UNIQUE (schedule_id, scheduled_for),
    CONSTRAINT ck_scheduler_execution_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_scheduler_execution_claim
    ON scheduler_execution (status, scheduled_for, id);

CREATE INDEX idx_scheduler_execution_stale
    ON scheduler_execution (started_at, id)
    WHERE status = 'RUNNING';

CREATE TABLE scheduler_task_history (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES scheduler_execution(id),
    schedule_id UUID NOT NULL REFERENCES scheduler_schedule(id),
    group_id UUID REFERENCES scheduler_task_group(id),
    task_id UUID NOT NULL REFERENCES scheduler_task(id),
    task_name VARCHAR(150) NOT NULL,
    status VARCHAR(10) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    threshold_ms BIGINT NOT NULL,
    threshold_exceeded BOOLEAN NOT NULL,
    http_status_code INTEGER,
    error_message VARCHAR(1000),
    CONSTRAINT uk_scheduler_execution_task UNIQUE (execution_id, task_id),
    CONSTRAINT ck_scheduler_task_history_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_scheduler_task_history_duration_nonnegative CHECK (duration_ms >= 0),
    CONSTRAINT ck_scheduler_task_history_threshold_positive CHECK (threshold_ms > 0),
    CONSTRAINT ck_scheduler_task_history_http_status
        CHECK (http_status_code IS NULL OR http_status_code BETWEEN 100 AND 599)
);

CREATE INDEX idx_scheduler_history_started
    ON scheduler_task_history (started_at DESC, id DESC);

CREATE INDEX idx_scheduler_history_group_started
    ON scheduler_task_history (group_id, started_at DESC)
    WHERE group_id IS NOT NULL;

CREATE INDEX idx_scheduler_history_task_started
    ON scheduler_task_history (task_id, started_at DESC);

CREATE INDEX idx_scheduler_history_threshold_started
    ON scheduler_task_history (threshold_exceeded, started_at DESC);
