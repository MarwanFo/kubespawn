-- KubeSpawn Phase 10: QA/Reviewer Workflow and Immutable Decisions
-- Tables for review requests, reviewer assignments, test checklists, comments, and immutable decisions.

CREATE TABLE IF NOT EXISTS review_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    requester_actor VARCHAR(100) NOT NULL,
    target_revision VARCHAR(100) NOT NULL,
    pr_number INTEGER,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_request_status CHECK (status IN ('PENDING', 'APPROVED', 'CHANGES_REQUESTED', 'STALE', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_review_requests_lookup 
ON review_requests(tenant_id, project_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_review_requests_env 
ON review_requests(environment_id, created_at DESC);

CREATE TABLE IF NOT EXISTS review_assignments (
    id UUID PRIMARY KEY,
    review_request_id UUID NOT NULL REFERENCES review_requests(id) ON DELETE CASCADE,
    reviewer_actor VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ASSIGNED',
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_assignment_status CHECK (status IN ('ASSIGNED', 'COMPLETED', 'UNASSIGNED'))
);

CREATE INDEX IF NOT EXISTS idx_review_assignments_actor 
ON review_assignments(reviewer_actor, status);

CREATE TABLE IF NOT EXISTS review_test_checklists (
    id UUID PRIMARY KEY,
    review_request_id UUID NOT NULL REFERENCES review_requests(id) ON DELETE CASCADE,
    item_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_review_checklist_item UNIQUE (review_request_id, item_key)
);

CREATE TABLE IF NOT EXISTS review_comments (
    id UUID PRIMARY KEY,
    review_request_id UUID NOT NULL REFERENCES review_requests(id) ON DELETE CASCADE,
    author_actor VARCHAR(100) NOT NULL,
    author_role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS review_decisions (
    id UUID PRIMARY KEY,
    review_request_id UUID NOT NULL REFERENCES review_requests(id) ON DELETE CASCADE,
    decision VARCHAR(32) NOT NULL,
    decision_actor VARCHAR(100) NOT NULL,
    reviewed_revision VARCHAR(100) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_decision_type CHECK (decision IN ('APPROVED', 'CHANGES_REQUESTED'))
);

CREATE INDEX IF NOT EXISTS idx_review_decisions_req 
ON review_decisions(review_request_id, created_at DESC);

-- Deterministic fixtures for Phase 10:
-- Acme Dave Developer requested QA review for environment 10000000-...-0031
-- Assigned to Quentin QA Reviewer (acme-qa)

INSERT INTO review_requests (
    id, tenant_id, project_id, environment_id,
    requester_actor, target_revision, pr_number, title, status,
    created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000041',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000031',
    'acme-dev',
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4',
    42,
    'PR #42: Add shopping cart badge and checkout summary',
    'PENDING',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
) ON CONFLICT DO NOTHING;

INSERT INTO review_assignments (
    id, review_request_id, reviewer_actor, status, assigned_at
) VALUES (
    '10000000-0000-0000-0000-000000000042',
    '10000000-0000-0000-0000-000000000041',
    'acme-qa',
    'ASSIGNED',
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
) ON CONFLICT DO NOTHING;

-- Also assign to local-developer for local developer testing
INSERT INTO review_assignments (
    id, review_request_id, reviewer_actor, status, assigned_at
) VALUES (
    '10000000-0000-0000-0000-000000000043',
    '10000000-0000-0000-0000-000000000041',
    'local-developer',
    'ASSIGNED',
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
) ON CONFLICT DO NOTHING;

INSERT INTO review_test_checklists (
    id, review_request_id, item_key, title, passed, updated_by, updated_at
) VALUES 
(
    '10000000-0000-0000-0000-000000000051',
    '10000000-0000-0000-0000-000000000041',
    'anonymous-denial',
    'Edge routing denies anonymous requests',
    true,
    'acme-qa',
    CURRENT_TIMESTAMP - INTERVAL '45 minutes'
),
(
    '10000000-0000-0000-0000-000000000052',
    '10000000-0000-0000-0000-000000000041',
    'authenticated-access',
    'Authenticated preview session accesses edge probe route',
    true,
    'acme-qa',
    CURRENT_TIMESTAMP - INTERVAL '45 minutes'
),
(
    '10000000-0000-0000-0000-000000000053',
    '10000000-0000-0000-0000-000000000041',
    'responsive-layout',
    'Responsive layouts verified on mobile and desktop',
    false,
    'acme-qa',
    CURRENT_TIMESTAMP - INTERVAL '45 minutes'
),
(
    '10000000-0000-0000-0000-000000000054',
    '10000000-0000-0000-0000-000000000041',
    'tenant-isolation',
    'No cross-tenant data visible in state',
    false,
    'acme-qa',
    CURRENT_TIMESTAMP - INTERVAL '45 minutes'
) ON CONFLICT DO NOTHING;

INSERT INTO review_comments (
    id, review_request_id, author_actor, author_role, content, created_at
) VALUES (
    '10000000-0000-0000-0000-000000000061',
    '10000000-0000-0000-0000-000000000041',
    'acme-qa',
    'QA_REVIEWER',
    'Edge probe passed with 200 OK. Testing responsive shopping cart drawers next.',
    CURRENT_TIMESTAMP - INTERVAL '30 minutes'
) ON CONFLICT DO NOTHING;
