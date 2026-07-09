CREATE TABLE task_audit_events (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(50) NOT NULL,
    to_status VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    thread_name VARCHAR(255) NOT NULL,
    message TEXT,
    CONSTRAINT fk_task_audit_events_task FOREIGN KEY (task_id) REFERENCES tasks (task_id) ON DELETE CASCADE
);

CREATE INDEX idx_task_audit_events_task_id ON task_audit_events (task_id);
