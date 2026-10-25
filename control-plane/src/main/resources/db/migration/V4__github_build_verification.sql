CREATE TABLE github_repositories (
    repository_id BIGINT PRIMARY KEY CHECK (repository_id > 0),
    installation_id BIGINT NOT NULL CHECK (installation_id > 0),
    full_name VARCHAR(255) NOT NULL UNIQUE,
    allowed_branch VARCHAR(255) NOT NULL,
    workflow_id BIGINT NOT NULL CHECK (workflow_id > 0),
    workflow_path VARCHAR(255) NOT NULL,
    workflow_sha256 CHAR(64) NOT NULL,
    api_image VARCHAR(255) NOT NULL,
    web_image VARCHAR(255) NOT NULL,
    chart_revision CHAR(40) NOT NULL,
    template_hash CHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE github_build_requests (
    id UUID PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES github_repositories(repository_id),
    source_sha CHAR(40) NOT NULL,
    policy_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','DISPATCHED','VERIFIED')),
    job_id UUID NOT NULL REFERENCES jobs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repository_id, source_sha, policy_version)
);

CREATE TABLE github_webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    installation_id BIGINT,
    repository_id BIGINT,
    run_id BIGINT,
    run_attempt INTEGER,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','IGNORED','VERIFIED','REJECTED')),
    job_id UUID REFERENCES jobs(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE verified_builds (
    id UUID PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES github_repositories(repository_id),
    source_sha CHAR(40) NOT NULL,
    policy_version BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    run_attempt INTEGER NOT NULL,
    workflow_revision CHAR(40) NOT NULL,
    workflow_sha256 CHAR(64) NOT NULL,
    artifact_id BIGINT NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    api_image VARCHAR(255) NOT NULL,
    api_digest VARCHAR(71) NOT NULL,
    web_image VARCHAR(255) NOT NULL,
    web_digest VARCHAR(71) NOT NULL,
    chart_revision CHAR(40) NOT NULL,
    template_hash CHAR(64) NOT NULL,
    evidence JSONB NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repository_id, run_id, run_attempt, policy_version)
);
CREATE INDEX verified_source_builds ON verified_builds(repository_id, source_sha, policy_version, verified_at DESC);
ALTER TABLE environments ADD COLUMN verified_build_id UUID REFERENCES verified_builds(id);

-- Repository jobs use their own job lease; environment serialization does not apply to them.
ALTER TABLE jobs DROP CONSTRAINT running_job_has_lease;
ALTER TABLE jobs ADD CONSTRAINT running_job_has_lease CHECK
    (state <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
        AND (environment_id IS NULL OR environment_lease_token IS NOT NULL)));
