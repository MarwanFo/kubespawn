CREATE TABLE environment_sources (
    id UUID PRIMARY KEY,
    tenant_key VARCHAR(100) NOT NULL,
    project_key VARCHAR(100) NOT NULL,
    installation_id BIGINT,
    repository_id BIGINT,
    repository_name VARCHAR(255) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('PR', 'MANUAL')),
    pr_number INTEGER,
    manual_request_id UUID,
    requested_ref VARCHAR(255) NOT NULL,
    source_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (source_state IN ('UNKNOWN', 'OPEN', 'CLOSED', 'MANUAL')),
    suppression_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (source_kind = 'PR' AND pr_number IS NOT NULL AND pr_number > 0 AND repository_id IS NOT NULL
            AND installation_id IS NOT NULL AND manual_request_id IS NULL)
        OR (source_kind = 'MANUAL' AND pr_number IS NULL AND manual_request_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX unique_pr_source
    ON environment_sources(installation_id, repository_id, pr_number) WHERE source_kind = 'PR';
CREATE UNIQUE INDEX unique_manual_source
    ON environment_sources(tenant_key, manual_request_id) WHERE source_kind = 'MANUAL';

CREATE TABLE environments (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES environment_sources(id),
    repository_name VARCHAR(255) NOT NULL,
    cluster_id VARCHAR(100) NOT NULL,
    namespace VARCHAR(63) NOT NULL,
    desired_state VARCHAR(16) NOT NULL CHECK (desired_state IN ('ACTIVE', 'ABSENT')),
    phase VARCHAR(32) NOT NULL CHECK (phase IN (
        'WAITING_BUILD', 'QUEUED', 'PROVISIONING', 'READY', 'UPDATING', 'DEGRADED',
        'FAILED', 'TERMINATING', 'CLEANUP_BLOCKED', 'DESTROYED'
    )),
    intent_version BIGINT NOT NULL DEFAULT 0 CHECK (intent_version >= 0),
    requested_revision VARCHAR(64),
    serving_revision VARCHAR(64),
    chart_revision VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    destroyed_at TIMESTAMPTZ,
    UNIQUE (cluster_id, namespace),
    CHECK (expires_at > created_at),
    CHECK ((phase = 'DESTROYED') = (destroyed_at IS NOT NULL)),
    CHECK (destroyed_at IS NULL OR desired_state = 'ABSENT')
);

CREATE UNIQUE INDEX one_live_generation_per_source ON environments(source_id) WHERE destroyed_at IS NULL;
CREATE INDEX environments_expiry ON environments(expires_at) WHERE desired_state = 'ACTIVE';
CREATE INDEX environments_recent ON environments(created_at DESC, id DESC);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    environment_id UUID REFERENCES environments(id),
    job_type VARCHAR(64) NOT NULL,
    intent_version BIGINT NOT NULL CHECK (intent_version >= 0),
    deduplication_key VARCHAR(255) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETE', 'FAILED')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(255),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX claimable_jobs ON jobs(available_at, id) WHERE state = 'PENDING';

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX unpublished_outbox ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    environment_id UUID,
    actor VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Deliberately no cascading environment foreign key: audit retention is independent.
CREATE INDEX environment_audit_history ON audit_events(environment_id, created_at);

