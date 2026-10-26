CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(320),
    display_name VARCHAR(120) NOT NULL,
    avatar_url VARCHAR(2048),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    platform_role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (platform_role IN ('USER','ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX app_users_email ON app_users(lower(email)) WHERE email IS NOT NULL;

CREATE TABLE user_identities (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ,
    PRIMARY KEY (issuer, subject),
    UNIQUE (user_id, issuer)
);

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    tenant_key VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tenant_memberships (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('VIEWER','DEVELOPER','MAINTAINER','TENANT_ADMIN')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX tenant_memberships_user ON tenant_memberships(user_id, status);

-- The local profile is a real persisted identity, not an authorization bypass. OIDC identities
-- are provisioned by an administrator before their first production login.
INSERT INTO app_users(id, email, display_name, platform_role)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'Local Administrator', 'ADMIN');
INSERT INTO user_identities(user_id, issuer, subject)
VALUES ('00000000-0000-0000-0000-000000000001', 'local', 'local-developer');
INSERT INTO tenants(id, tenant_key, name)
VALUES ('00000000-0000-0000-0000-000000000002', 'local', 'Local workspace');
INSERT INTO tenant_memberships(tenant_id, user_id, role)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'TENANT_ADMIN');

CREATE INDEX environment_sources_tenant ON environment_sources(tenant_key, created_at DESC);
