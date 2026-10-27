-- KubeSpawn Phase 13: Usage, plan entitlements, and commercial readiness

-- 1. Plans Catalog
CREATE TABLE IF NOT EXISTS plans (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tier VARCHAR(32) NOT NULL,
    description TEXT NOT NULL,
    max_environments INT NOT NULL,
    max_queued_environments INT NOT NULL,
    cpu_millicores_quota INT NOT NULL,
    memory_mb_quota INT NOT NULL,
    storage_mb_quota INT NOT NULL,
    max_ttl_hours INT NOT NULL,
    price_cents_monthly INT NOT NULL DEFAULT 0,
    cpu_rate_cents_per_core_hour NUMERIC(10,4) NOT NULL DEFAULT 5.0000,
    memory_rate_cents_per_gb_hour NUMERIC(10,4) NOT NULL DEFAULT 1.0000,
    features JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT true,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed Standard Plans
INSERT INTO plans (
    id, name, tier, description, max_environments, max_queued_environments,
    cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours,
    price_cents_monthly, cpu_rate_cents_per_core_hour, memory_rate_cents_per_gb_hour,
    features, is_active, version
) VALUES
(
    'FREE',
    'Starter / Free',
    'FREE',
    'Essential developer preview platform for open source and small teams.',
    3,
    5,
    4000,
    8192,
    20480,
    24,
    0,
    5.0000,
    1.0000,
    '["3 Active Preview Environments", "4 CPU Cores & 8GB RAM Quota", "24h Max Environment TTL", "Standard Community Support"]'::jsonb,
    true,
    1
),
(
    'PRO',
    'Pro Team',
    'PRO',
    'Collaborative development environments with team reviews, quotas, and fast deployments.',
    10,
    20,
    16000,
    32768,
    102400,
    72,
    4900,
    4.0000,
    0.8000,
    '["10 Active Preview Environments", "16 CPU Cores & 32GB RAM Quota", "72h Max Environment TTL", "QA Review Workflows", "Priority Recovery Support", "Audit History & FinOps Exports"]'::jsonb,
    true,
    1
),
(
    'ENTERPRISE',
    'Enterprise Scale',
    'ENTERPRISE',
    'High-concurrency infrastructure with dedicated capacity reservation and custom SLAs.',
    50,
    100,
    64000,
    131072,
    512000,
    168,
    19900,
    3.0000,
    0.6000,
    '["50 Active Preview Environments", "64 CPU Cores & 128GB RAM Quota", "7 Days Max Environment TTL", "Custom Domain Routing", "Dedicated Operational Support", "Unlimited Team Collaborators"]'::jsonb,
    true,
    1
)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    max_environments = EXCLUDED.max_environments,
    cpu_millicores_quota = EXCLUDED.cpu_millicores_quota,
    memory_mb_quota = EXCLUDED.memory_mb_quota,
    storage_mb_quota = EXCLUDED.storage_mb_quota,
    max_ttl_hours = EXCLUDED.max_ttl_hours,
    features = EXCLUDED.features;

-- 2. Tenant Subscriptions
CREATE TABLE IF NOT EXISTS tenant_subscriptions (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    plan_id VARCHAR(64) NOT NULL REFERENCES plans(id),
    plan_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED')),
    current_period_start TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_period_end TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days'),
    billing_provider VARCHAR(64) NOT NULL DEFAULT 'INTERNAL',
    billing_customer_id VARCHAR(255),
    billing_subscription_id VARCHAR(255),
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tenant_subscriptions_plan ON tenant_subscriptions(plan_id, status);

-- Seed Tenant Subscriptions
INSERT INTO tenant_subscriptions (
    tenant_id, plan_id, plan_version, status, current_period_start, current_period_end, billing_provider
) VALUES
(
    '10000000-0000-0000-0000-000000000001'::uuid,
    'PRO',
    1,
    'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '15 days',
    CURRENT_TIMESTAMP + INTERVAL '15 days',
    'INTERNAL'
),
(
    '20000000-0000-0000-0000-000000000001'::uuid,
    'FREE',
    1,
    'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '5 days',
    CURRENT_TIMESTAMP + INTERVAL '25 days',
    'INTERNAL'
)
ON CONFLICT (tenant_id) DO NOTHING;

-- 3. Measured Usage Intervals
CREATE TABLE IF NOT EXISTS usage_intervals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment_id UUID REFERENCES environments(id) ON DELETE SET NULL,
    environment_name VARCHAR(255) NOT NULL,
    cpu_millicores INT NOT NULL DEFAULT 1000,
    memory_mb INT NOT NULL DEFAULT 2048,
    storage_mb INT NOT NULL DEFAULT 5120,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    duration_seconds BIGINT,
    core_hours NUMERIC(12,4) NOT NULL DEFAULT 0.0000,
    memory_gb_hours NUMERIC(12,4) NOT NULL DEFAULT 0.0000,
    estimated_cost_cents INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED', 'RECONCILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_usage_intervals_tenant_period
    ON usage_intervals(tenant_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_usage_intervals_project_period
    ON usage_intervals(project_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_usage_intervals_env_active
    ON usage_intervals(environment_id)
    WHERE status = 'ACTIVE';

-- Backfill usage intervals for existing environments
INSERT INTO usage_intervals (
    id, tenant_id, project_id, environment_id, environment_name,
    cpu_millicores, memory_mb, storage_mb, started_at, ended_at,
    duration_seconds, core_hours, memory_gb_hours, estimated_cost_cents, status
)
SELECT
    gen_random_uuid(),
    e.tenant_id,
    e.project_id,
    e.id,
    COALESCE(e.repository_name, 'preview-environment'),
    COALESCE(e.cpu_millicores, 1000),
    COALESCE(e.memory_mb, 2048),
    COALESCE(e.storage_mb, 5120),
    e.created_at,
    CASE WHEN e.phase = 'DESTROYED' THEN COALESCE(e.destroyed_at, e.updated_at, CURRENT_TIMESTAMP) ELSE NULL END,
    CASE WHEN e.phase = 'DESTROYED'
         THEN GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, CURRENT_TIMESTAMP) - e.created_at))::bigint)
         ELSE NULL END,
    CASE WHEN e.phase = 'DESTROYED'
         THEN ROUND((COALESCE(e.cpu_millicores, 1000)::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, CURRENT_TIMESTAMP) - e.created_at))) / 3600.0), 4)
         ELSE 0.0000 END,
    CASE WHEN e.phase = 'DESTROYED'
         THEN ROUND((COALESCE(e.memory_mb, 2048)::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, CURRENT_TIMESTAMP) - e.created_at))) / 3600.0), 4)
         ELSE 0.0000 END,
    0,
    CASE WHEN e.phase = 'DESTROYED' THEN 'CLOSED' ELSE 'ACTIVE' END
FROM environments e
WHERE NOT EXISTS (
    SELECT 1 FROM usage_intervals u WHERE u.environment_id = e.id
);

-- 4. Deduplicated Billing Webhook Events
CREATE TABLE IF NOT EXISTS billing_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED' CHECK (status IN ('PROCESSED', 'FAILED', 'IGNORED')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_billing_events_idempotency ON billing_events(idempotency_key);
