CREATE TABLE tasks (
    task_id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    priority VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    payload TEXT,
    submitted_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    error_message TEXT,
    result TEXT,
    idempotency_key VARCHAR(255) UNIQUE
);

CREATE INDEX idx_tasks_status ON tasks (status);
