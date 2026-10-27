-- Phase 01: Engineering setup and migration baseline
-- Additive migration: Target SaaS roles, projects, project memberships, and onboarding status.

-- 1. Extend app_users with onboarding status and security version
ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS onboarding_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
        CHECK (onboarding_status IN ('PENDING_ONBOARDING', 'COMPLETED')),
    ADD COLUMN IF NOT EXISTS security_version INT NOT NULL DEFAULT 1;

-- 2. Migrate legacy roles to target SaaS roles:
-- Drop old constraint first to allow new role values during update
ALTER TABLE tenant_memberships DROP CONSTRAINT IF EXISTS tenant_memberships_role_check;

-- Migrate legacy role values:
--    TENANT_ADMIN -> PORTAL_ADMIN
--    VIEWER -> QA_REVIEWER
--    MAINTAINER -> DEVELOPER
UPDATE tenant_memberships SET role = 'PORTAL_ADMIN' WHERE role = 'TENANT_ADMIN';
UPDATE tenant_memberships SET role = 'QA_REVIEWER' WHERE role = 'VIEWER';
UPDATE tenant_memberships SET role = 'DEVELOPER' WHERE role = 'MAINTAINER';

-- Re-add the check constraint to strictly enforce the three SaaS target roles
ALTER TABLE tenant_memberships ADD CONSTRAINT tenant_memberships_role_check
    CHECK (role IN ('DEVELOPER', 'QA_REVIEWER', 'PORTAL_ADMIN'));

-- 3. Create projects aggregate
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_key VARCHAR(100) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, project_key)
);
CREATE INDEX IF NOT EXISTS projects_tenant ON projects(tenant_id, created_at DESC);

-- 4. Create project memberships table (project-level authorization)
CREATE TABLE IF NOT EXISTS project_memberships (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX IF NOT EXISTS project_memberships_user ON project_memberships(user_id, tenant_id);

-- 5. Backfill default project for existing local workspace
INSERT INTO projects(id, tenant_id, project_key, name, description)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000002',
    'default',
    'Default Project',
    'Default project for local development'
) ON CONFLICT DO NOTHING;

INSERT INTO project_memberships(project_id, user_id, tenant_id)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002'
) ON CONFLICT DO NOTHING;

-- 6. Deterministic test fixtures for Phase 01:
--    Two tenants (Acme & Globex), multiple projects, all 3 roles, suspended account, and onboarding user.

-- Tenant A: Acme Corp
INSERT INTO tenants(id, tenant_key, name)
VALUES ('10000000-0000-0000-0000-000000000001', 'acme', 'Acme Corporation')
ON CONFLICT DO NOTHING;

INSERT INTO projects(id, tenant_id, project_key, name, description) VALUES
    ('10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', 'web-app', 'Web Application', 'Customer-facing frontend application'),
    ('10000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001', 'backend-api', 'Backend API', 'Core microservices backend')
ON CONFLICT DO NOTHING;

-- Acme Users: Portal Admin, Developer, QA Reviewer, Suspended
INSERT INTO app_users(id, email, display_name, platform_role, status, onboarding_status) VALUES
    ('10000000-0000-0000-0000-000000000101', 'admin@acme.test', 'Alice Admin', 'ADMIN', 'ACTIVE', 'COMPLETED'),
    ('10000000-0000-0000-0000-000000000102', 'dev@acme.test', 'Dave Developer', 'USER', 'ACTIVE', 'COMPLETED'),
    ('10000000-0000-0000-0000-000000000103', 'qa@acme.test', 'Quinn Reviewer', 'USER', 'ACTIVE', 'COMPLETED'),
    ('10000000-0000-0000-0000-000000000104', 'suspended@acme.test', 'Sam Suspended', 'USER', 'SUSPENDED', 'COMPLETED')
ON CONFLICT DO NOTHING;

INSERT INTO user_identities(user_id, issuer, subject) VALUES
    ('10000000-0000-0000-0000-000000000101', 'https://issuer.test', 'acme-admin'),
    ('10000000-0000-0000-0000-000000000102', 'https://issuer.test', 'acme-dev'),
    ('10000000-0000-0000-0000-000000000103', 'https://issuer.test', 'acme-qa'),
    ('10000000-0000-0000-0000-000000000104', 'https://issuer.test', 'acme-suspended')
ON CONFLICT DO NOTHING;

INSERT INTO tenant_memberships(tenant_id, user_id, role, status) VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000101', 'PORTAL_ADMIN', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000102', 'DEVELOPER', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000103', 'QA_REVIEWER', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000104', 'DEVELOPER', 'SUSPENDED')
ON CONFLICT DO NOTHING;

INSERT INTO project_memberships(project_id, user_id, tenant_id) VALUES
    ('10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000001'), -- Dave on web-app
    ('10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000001'), -- Quinn on web-app
    ('10000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000001')  -- Quinn on backend-api
ON CONFLICT DO NOTHING;

-- Tenant B: Globex Corp
INSERT INTO tenants(id, tenant_key, name)
VALUES ('20000000-0000-0000-0000-000000000001', 'globex', 'Globex Corporation')
ON CONFLICT DO NOTHING;

INSERT INTO projects(id, tenant_id, project_key, name, description) VALUES
    ('20000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000001', 'core-platform', 'Core Platform', 'Enterprise platform services')
ON CONFLICT DO NOTHING;

INSERT INTO app_users(id, email, display_name, platform_role, status, onboarding_status) VALUES
    ('20000000-0000-0000-0000-000000000101', 'admin@globex.test', 'Grace Admin', 'ADMIN', 'ACTIVE', 'COMPLETED'),
    ('20000000-0000-0000-0000-000000000102', 'dev@globex.test', 'Dan Developer', 'USER', 'ACTIVE', 'COMPLETED')
ON CONFLICT DO NOTHING;

INSERT INTO user_identities(user_id, issuer, subject) VALUES
    ('20000000-0000-0000-0000-000000000101', 'https://issuer.test', 'globex-admin'),
    ('20000000-0000-0000-0000-000000000102', 'https://issuer.test', 'globex-dev')
ON CONFLICT DO NOTHING;

INSERT INTO tenant_memberships(tenant_id, user_id, role, status) VALUES
    ('20000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000101', 'PORTAL_ADMIN', 'ACTIVE'),
    ('20000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000102', 'DEVELOPER', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO project_memberships(project_id, user_id, tenant_id) VALUES
    ('20000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000102', '20000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Onboarding User: Account verified, awaiting onboarding (no tenant memberships)
INSERT INTO app_users(id, email, display_name, platform_role, status, onboarding_status)
VALUES ('30000000-0000-0000-0000-000000000101', 'newbie@test.test', 'Ned Newbie', 'USER', 'ACTIVE', 'PENDING_ONBOARDING')
ON CONFLICT DO NOTHING;

INSERT INTO user_identities(user_id, issuer, subject)
VALUES ('30000000-0000-0000-0000-000000000101', 'https://issuer.test', 'newbie-user')
ON CONFLICT DO NOTHING;
