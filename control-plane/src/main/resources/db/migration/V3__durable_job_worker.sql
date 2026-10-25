ALTER TABLE jobs DROP CONSTRAINT jobs_state_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_state_check
    CHECK (state IN ('PENDING', 'RUNNING', 'WAITING', 'COMPLETE', 'FAILED', 'SUPERSEDED'));
ALTER TABLE jobs ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count >= 0);
ALTER TABLE jobs ADD COLUMN reason VARCHAR(64) NOT NULL DEFAULT 'QUEUED';
ALTER TABLE jobs ADD COLUMN message VARCHAR(512) NOT NULL DEFAULT 'Waiting for the job worker.';
ALTER TABLE jobs ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE jobs ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN last_heartbeat_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN environment_lease_token BIGINT;

-- Upgrade incomplete leases from an earlier, interrupted worker experiment safely.
UPDATE jobs SET state='PENDING', lease_owner=NULL, lease_expires_at=NULL WHERE state='RUNNING';
ALTER TABLE jobs ADD CONSTRAINT running_job_has_lease CHECK
    (state <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
        AND environment_lease_token IS NOT NULL));
DROP INDEX claimable_jobs;
CREATE INDEX claimable_jobs ON jobs(available_at, id) WHERE state IN ('PENDING', 'WAITING');
CREATE INDEX expired_job_leases ON jobs(lease_expires_at, id) WHERE state='RUNNING';
CREATE INDEX environment_operations ON jobs(environment_id, intent_version DESC, created_at DESC, id DESC);

-- Keep this row after release: its fencing token must never reset between jobs.
CREATE TABLE environment_work_leases (
    environment_id UUID PRIMARY KEY REFERENCES environments(id),
    job_id UUID REFERENCES jobs(id),
    worker_id VARCHAR(255),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((job_id IS NULL) = (worker_id IS NULL))
);
