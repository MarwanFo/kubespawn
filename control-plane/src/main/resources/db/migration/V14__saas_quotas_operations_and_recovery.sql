-- KubeSpawn Phase 11: Portal Admin/DevOps Operations, Quotas, and Recovery
-- Tables for tenant/project quotas, resource allocation, recovery operations, and scoped audit events.

-- 1. Extend environments with resource allocations
ALTER TABLE environments
    ADD COLUMN IF NOT EXISTS cpu_millicores INT NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS memory_mb INT NOT NULL DEFAULT 2048,
    ADD COLUMN IF NOT EXISTS storage_mb INT NOT NULL DEFAULT 5120;

-- 2. Extend audit_events with tenant and project scoping
ALTER TABLE audit_events
    ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_audit_events_tenant ON audit_events(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_events_project ON audit_events(project_id, created_at DESC);

-- Backfill audit events from environments
UPDATE audit_events a
SET tenant_id = e.tenant_id,
    project_id = e.project_id
FROM environments e
WHERE a.environment_id = e.id AND a.tenant_id IS NULL;

-- 3. Tenant-level Quotas and Capacity Ceilings
CREATE TABLE IF NOT EXISTS tenant_quotas (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    max_environments INT NOT NULL DEFAULT 5,
    max_queued_environments INT NOT NULL DEFAULT 10,
    cpu_millicores_quota INT NOT NULL DEFAULT 8000,
    memory_mb_quota INT NOT NULL DEFAULT 16384,
    storage_mb_quota INT NOT NULL DEFAULT 51200,
    max_ttl_hours INT NOT NULL DEFAULT 72,
    policy_version INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    CONSTRAINT chk_tenant_quota_limits CHECK (
        max_environments > 0 AND max_queued_environments >= 0 AND
        cpu_millicores_quota > 0 AND memory_mb_quota > 0 AND
        max_ttl_hours > 0 AND policy_version > 0
    )
);

-- 4. Project-level Quotas
CREATE TABLE IF NOT EXISTS project_quotas (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    max_environments INT NOT NULL DEFAULT 3,
    cpu_millicores_quota INT NOT NULL DEFAULT 4000,
    memory_mb_quota INT NOT NULL DEFAULT 8192,
    storage_mb_quota INT NOT NULL DEFAULT 20480,
    max_ttl_hours INT NOT NULL DEFAULT 72,
    policy_version INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    CONSTRAINT chk_project_quota_limits CHECK (
        max_environments > 0 AND cpu_millicores_quota > 0 AND
        memory_mb_quota > 0 AND max_ttl_hours > 0 AND policy_version > 0
    )
);

-- 5. Approved Recovery Operations
CREATE TABLE IF NOT EXISTS recovery_operations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment_id UUID REFERENCES environments(id) ON DELETE SET NULL,
    operation_type VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    actor VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    outcome_details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_recovery_op_type CHECK (operation_type IN ('RETRY_OPERATION', 'RECONCILE_ENVIRONMENT', 'FORCE_CLEANUP'))
);

CREATE INDEX IF NOT EXISTS idx_recovery_operations_tenant ON recovery_operations(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recovery_operations_project ON recovery_operations(project_id, created_at DESC);

-- Deterministic fixtures for Phase 11:
-- Quotas for Local Workspace and Acme Corp

INSERT INTO tenant_quotas (
    tenant_id, max_environments, max_queued_environments,
    cpu_millicores_quota, memory_mb_quota, storage_mb_quota,
    max_ttl_hours, policy_version, updated_at, updated_by
) VALUES 
(
    '00000000-0000-0000-0000-000000000002',
    50, 50, 64000, 131072, 512000, 72, 1, CURRENT_TIMESTAMP, 'system'
),
(
    '10000000-0000-0000-0000-000000000001',
    5, 10, 8000, 16384, 51200, 72, 1, CURRENT_TIMESTAMP, 'system'
),
(
    '20000000-0000-0000-0000-000000000001',
    3, 5, 4000, 8192, 20480, 48, 1, CURRENT_TIMESTAMP, 'system'
) ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO project_quotas (
    project_id, tenant_id, max_environments,
    cpu_millicores_quota, memory_mb_quota, storage_mb_quota,
    max_ttl_hours, policy_version, updated_at, updated_by
) VALUES 
(
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000002',
    50, 64000, 131072, 512000, 72, 1, CURRENT_TIMESTAMP, 'system'
),
(
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000001',
    4, 6000, 12288, 30720, 72, 1, CURRENT_TIMESTAMP, 'system'
),
(
    '10000000-0000-0000-0000-000000000012',
    '10000000-0000-0000-0000-000000000001',
    2, 3000, 6144, 15360, 48, 1, CURRENT_TIMESTAMP, 'system'
) ON CONFLICT (project_id) DO NOTHING;

-- Fixture for a failed environment and job to demonstrate Portal Admin recovery
INSERT INTO environment_sources (
    id, tenant_id, project_id, tenant_key, project_key,
    installation_id, repository_id, repository_name, source_kind,
    pr_number, requested_ref, source_state, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000022',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    'acme',
    'web-app',
    1001,
    101010102,
    'acme/inventory-service',
    'PR',
    88,
    'fix/stock-sync',
    'OPEN',
    CURRENT_TIMESTAMP - INTERVAL '3 hours',
    CURRENT_TIMESTAMP - INTERVAL '3 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO environments (
    id, tenant_id, project_id, source_id, repository_name, cluster_id,
    namespace, desired_state, phase, intent_version,
    owner_actor, requested_ref, requested_revision, serving_revision,
    cpu_millicores, memory_mb, storage_mb,
    expires_at, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000032',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000022',
    'acme/inventory-service',
    'local',
    'ks-preview-88',
    'ACTIVE',
    'FAILED',
    1,
    'acme-dev',
    'fix/stock-sync',
    'b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5',
    NULL,
    1000, 2048, 5120,
    CURRENT_TIMESTAMP + INTERVAL '20 hours',
    CURRENT_TIMESTAMP - INTERVAL '3 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO jobs (
    id, environment_id, job_type, intent_version, deduplication_key, state,
    attempts, failure_count, last_error, available_at,
    created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000071',
    '10000000-0000-0000-0000-000000000032',
    'PROVISION_ENVIRONMENT',
    1,
    '10000000-0000-0000-0000-000000000032:1:PROVISION_ENVIRONMENT',
    'FAILED',
    3,
    3,
    'Argo CD application reconciliation timed out after 3 retries.',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '3 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO audit_events (
    id, tenant_id, project_id, environment_id, actor, action, request_id, details, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000032',
    'system-worker',
    'OPERATION_FAILED',
    'req-auto-recovery-test',
    '{"reason": "Argo CD timeout", "jobId": "10000000-0000-0000-0000-000000000071"}'::jsonb,
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant_memberships (user_id, tenant_id, role, status)
VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'PORTAL_ADMIN', 'ACTIVE')
ON CONFLICT DO NOTHING;

