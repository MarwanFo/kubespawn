-- KubeSpawn Phase 06: Tenant invitations and member administration

CREATE TABLE IF NOT EXISTS tenant_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(20) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    invited_by_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP WITH TIME ZONE,
    accepted_by_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT tenant_invitations_role_check CHECK (role IN ('DEVELOPER', 'QA_REVIEWER', 'PORTAL_ADMIN')),
    CONSTRAINT tenant_invitations_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_tenant_invitations_tenant ON tenant_invitations(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_invitations_email ON tenant_invitations(LOWER(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_pending_invite_email ON tenant_invitations(tenant_id, LOWER(email)) WHERE status = 'PENDING';

-- Deterministic fixtures for Phase 06 testing
-- Invitation 1: Pending DEVELOPER invite for Acme
INSERT INTO tenant_invitations (
    id, tenant_id, email, role, token_hash, status, expires_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000901',
    '10000000-0000-0000-0000-000000000001',
    'invite-dev@acme.test',
    'DEVELOPER',
    '6dcf9171e586e83f56ac34f2ae2a70ca117a8a816285947ff07cc99ac1b5c8cd',
    'PENDING',
    CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Invitation 2: Pending QA_REVIEWER invite for Acme
INSERT INTO tenant_invitations (
    id, tenant_id, email, role, token_hash, status, expires_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000902',
    '10000000-0000-0000-0000-000000000001',
    'invite-qa@acme.test',
    'QA_REVIEWER',
    '0f0b019c782b209a737241656dc5da601a8c799aa780fb15e098e2145d9166f3',
    'PENDING',
    CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Invitation 3: Expired invite for Acme
INSERT INTO tenant_invitations (
    id, tenant_id, email, role, token_hash, status, expires_at, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000903',
    '10000000-0000-0000-0000-000000000001',
    'expired@acme.test',
    'DEVELOPER',
    'dd10b45c40da6f945d0a51510a6dbb0e68f4555c21aa72a025415557770d540b',
    'PENDING',
    CURRENT_TIMESTAMP - INTERVAL '1 day',
    CURRENT_TIMESTAMP - INTERVAL '8 days'
) ON CONFLICT DO NOTHING;
