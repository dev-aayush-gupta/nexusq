CREATE TABLE jobs
(
    id         UUID        NOT NULL PRIMARY KEY,
    payload    JSONB       NOT NULL,
    status     VARCHAR(20) NOT NULL,
    priority   VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Serves the worker's claim query: WHERE status = 'PENDING' ORDER BY created_at
CREATE INDEX idx_jobs_status_created_at ON jobs (status, created_at);
