-- KubeSpawn Phase 08: Scoped Preview Environments and Developer Workflow
-- Binds environment_sources and environments to tenants and projects with full foreign keys.

ALTER TABLE environment_sources
    ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE environments
    ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_environment_sources_tenant_project ON environment_sources(tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_environments_tenant_project ON environments(tenant_id, project_id, created_at DESC);

-- Backfill default local workspace project for any unassigned environments and sources
UPDATE environment_sources
SET tenant_id = '00000000-0000-0000-0000-000000000002',
    project_id = '00000000-0000-0000-0000-000000000003'
WHERE tenant_id IS NULL;

UPDATE environments
SET tenant_id = '00000000-0000-0000-0000-000000000002',
    project_id = '00000000-0000-0000-0000-000000000003'
WHERE tenant_id IS NULL;

-- Deterministic fixtures for Phase 08:
-- Dave Developer on Acme web-app project with an active, ready preview environment

INSERT INTO environment_sources (
    id, tenant_id, project_id, tenant_key, project_key,
    installation_id, repository_id, repository_name, source_kind,
    pr_number, requested_ref, source_state, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    'acme',
    'web-app',
    1001,
    101010101,
    'acme/web-frontend',
    'PR',
    42,
    'feature/shopping-cart',
    'OPEN',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO environments (
    id, tenant_id, project_id, source_id, repository_name, cluster_id,
    namespace, desired_state, phase, intent_version,
    owner_actor, requested_ref, requested_revision, serving_revision,
    expires_at, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000031',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000021',
    'acme/web-frontend',
    'local',
    'ks-acme-preview-42',
    'ACTIVE',
    'READY',
    1,
    'acme-dev',
    'feature/shopping-cart',
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4',
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4',
    CURRENT_TIMESTAMP + INTERVAL '22 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT (cluster_id, namespace) DO NOTHING;

INSERT INTO audit_events (
    id, environment_id, actor, action, request_id, details, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000041',
    '10000000-0000-0000-0000-000000000031',
    'acme-dev',
    'ENVIRONMENT_REQUESTED',
    '10000000-0000-0000-0000-000000000051',
    '{"branch": "feature/shopping-cart", "ttlHours": 24}'::jsonb,
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;
