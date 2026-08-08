CREATE TABLE scheduler_group_group (
    parent_group_id UUID NOT NULL
        REFERENCES scheduler_task_group(id) ON DELETE CASCADE,
    child_group_id UUID NOT NULL
        REFERENCES scheduler_task_group(id) ON DELETE RESTRICT,
    sequence_no INTEGER NOT NULL,
    PRIMARY KEY (parent_group_id, child_group_id),
    CONSTRAINT uk_scheduler_child_group_sequence
        UNIQUE (parent_group_id, sequence_no),
    CONSTRAINT ck_scheduler_group_not_self
        CHECK (parent_group_id <> child_group_id),
    CONSTRAINT ck_scheduler_child_group_sequence_positive
        CHECK (sequence_no > 0)
);

CREATE INDEX idx_scheduler_group_group_child
    ON scheduler_group_group (child_group_id);
