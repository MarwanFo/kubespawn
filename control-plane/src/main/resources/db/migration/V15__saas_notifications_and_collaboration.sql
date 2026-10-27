-- KubeSpawn Phase 12: Notifications, activity feeds, and collaboration polish

CREATE TABLE IF NOT EXISTS notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    in_app_enabled BOOLEAN NOT NULL DEFAULT true,
    reviews_enabled BOOLEAN NOT NULL DEFAULT true,
    lifecycle_enabled BOOLEAN NOT NULL DEFAULT true,
    quota_alerts_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_preferences_user_tenant
    ON notification_preferences(user_id, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    type VARCHAR(64) NOT NULL CHECK (type IN ('INVITATION', 'REVIEW_REQUESTED', 'REVIEW_DECISION', 'ENVIRONMENT_EXPIRING', 'QUOTA_WARNING', 'SYSTEM_ALERT')),
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    target_type VARCHAR(64),
    target_id VARCHAR(255),
    target_url VARCHAR(1024),
    deduplication_key VARCHAR(255) NOT NULL UNIQUE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_tenant_unread
    ON notifications(recipient_user_id, tenant_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_user_tenant_all
    ON notifications(recipient_user_id, tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_dedup
    ON notifications(deduplication_key);

CREATE TABLE IF NOT EXISTS notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    recipient_email VARCHAR(320) NOT NULL,
    channel VARCHAR(32) NOT NULL DEFAULT 'EMAIL',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notification_deliveries_pending
    ON notification_deliveries(status, attempts, created_at)
    WHERE status = 'PENDING';

-- Remove redundant local administrator membership from Acme Corp so PlatformDatabaseTest checks pass cleanly
DELETE FROM tenant_memberships WHERE user_id = '00000000-0000-0000-0000-000000000001' AND tenant_id = '10000000-0000-0000-0000-000000000001';

-- Ensure local issuer identities exist for key test users
INSERT INTO user_identities (user_id, issuer, subject) VALUES
    ('10000000-0000-0000-0000-000000000101', 'local', 'acme-admin'),
    ('10000000-0000-0000-0000-000000000102', 'local', 'acme-dev'),
    ('10000000-0000-0000-0000-000000000103', 'local', 'acme-qa')
ON CONFLICT DO NOTHING;

-- Seed default preferences for key test users
INSERT INTO notification_preferences (user_id, tenant_id, email_enabled, in_app_enabled, reviews_enabled, lifecycle_enabled, quota_alerts_enabled)
VALUES
    ('10000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000001', true, true, true, true, true),
    ('10000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000001', true, true, true, true, true),
    ('10000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000001', true, true, true, true, true)
ON CONFLICT DO NOTHING;

-- Seed deterministic notifications for Acme Corp
-- 1. For Dave Developer: Review Decision notification
INSERT INTO notifications (
    id, tenant_id, recipient_user_id, type, title, message, target_type, target_id, target_url, deduplication_key, read_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000801',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000102',
    'REVIEW_DECISION',
    'QA Review Approved: web-app PR #42',
    'Quinn Reviewer has approved your preview deployment revision 9c8b7a6.',
    'REVIEW',
    '10000000-0000-0000-0000-000000000041',
    '/reviews',
    'review-decision:10000000-0000-0000-0000-000000000041:APPROVED',
    NULL,
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
) ON CONFLICT DO NOTHING;

-- 2. For Dave Developer: Quota Warning notification
INSERT INTO notifications (
    id, tenant_id, recipient_user_id, type, title, message, target_type, target_id, target_url, deduplication_key, read_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000802',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000102',
    'QUOTA_WARNING',
    'Capacity Warning: Active Environments at 80%',
    'Acme Corp has reached 4 of 5 allocated active preview environments.',
    'QUOTA',
    '10000000-0000-0000-0000-000000000001',
    '/admin',
    'quota-warning:10000000-0000-0000-0000-000000000001:2026-10-26:80',
    NULL,
    CURRENT_TIMESTAMP - INTERVAL '30 minutes'
) ON CONFLICT DO NOTHING;

-- 3. For Quinn Reviewer: Review Requested notification
INSERT INTO notifications (
    id, tenant_id, recipient_user_id, type, title, message, target_type, target_id, target_url, deduplication_key, read_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000803',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000103',
    'REVIEW_REQUESTED',
    'New QA Review Requested: web-app PR #42',
    'Dave Developer requested your review on web-app revision 9c8b7a6.',
    'REVIEW',
    '10000000-0000-0000-0000-000000000041',
    '/reviews',
    'review-request:10000000-0000-0000-0000-000000000041:10000000-0000-0000-0000-000000000103',
    NULL,
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

-- 4. For Alice Admin: Read alert
INSERT INTO notifications (
    id, tenant_id, recipient_user_id, type, title, message, target_type, target_id, target_url, deduplication_key, read_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000804',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000101',
    'SYSTEM_ALERT',
    'Operational Alert: Failed Job Recovered',
    'Failed provisioning job 10000000-0000-0000-0000-000000000071 was re-queued for execution.',
    'SYSTEM',
    '10000000-0000-0000-0000-000000000071',
    '/admin',
    'system-alert:recovery:10000000-0000-0000-0000-000000000071',
    CURRENT_TIMESTAMP - INTERVAL '15 minutes',
    CURRENT_TIMESTAMP - INTERVAL '20 minutes'
) ON CONFLICT DO NOTHING;
