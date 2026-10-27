-- KubeSpawn Phase 07: Bind GitHub repositories to tenants and projects with policy versioning

ALTER TABLE github_repositories
    ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_github_repositories_project ON github_repositories(tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_github_repositories_installation ON github_repositories(installation_id);

-- Backfill default local project for any unassigned repositories
UPDATE github_repositories
SET tenant_id = '00000000-0000-0000-0000-000000000002',
    project_id = '00000000-0000-0000-0000-000000000003'
WHERE tenant_id IS NULL;

-- Deterministic fixtures for Phase 07:
-- 1. Acme project repository
INSERT INTO github_repositories (
    repository_id, tenant_id, project_id, installation_id, full_name, allowed_branch,
    workflow_id, workflow_path, workflow_sha256, api_image, web_image,
    chart_revision, template_hash, policy_version, enabled
) VALUES (
    101010101,
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    1001,
    'acme/web-frontend',
    'main',
    10001,
    '.github/workflows/preview.yml',
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    'ghcr.io/acme/api',
    'ghcr.io/acme/web',
    '0123456789abcdef0123456789abcdef01234567',
    'b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    1,
    true
) ON CONFLICT (repository_id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    project_id = EXCLUDED.project_id;

-- 2. Globex project repository
INSERT INTO github_repositories (
    repository_id, tenant_id, project_id, installation_id, full_name, allowed_branch,
    workflow_id, workflow_path, workflow_sha256, api_image, web_image,
    chart_revision, template_hash, policy_version, enabled
) VALUES (
    202020202,
    '20000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000011',
    2002,
    'globex/portal',
    'main',
    20002,
    '.github/workflows/preview.yml',
    'c1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    'ghcr.io/globex/api',
    'ghcr.io/globex/web',
    '1123456789abcdef0123456789abcdef01234567',
    'd1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    1,
    true
) ON CONFLICT (repository_id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    project_id = EXCLUDED.project_id;
