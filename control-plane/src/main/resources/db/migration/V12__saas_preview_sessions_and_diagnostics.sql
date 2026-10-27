-- KubeSpawn Phase 09: Authorized Previews, Diagnostics, and Live Progress
-- Creates single-use preview exchange ticket store for isolated, path-scoped preview sessions.

CREATE TABLE IF NOT EXISTS preview_exchange_tickets (
    ticket UUID PRIMARY KEY,
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    actor VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_preview_exchange_tickets_active 
ON preview_exchange_tickets(ticket, expires_at) 
WHERE consumed = FALSE;

CREATE INDEX IF NOT EXISTS idx_preview_exchange_tickets_env 
ON preview_exchange_tickets(environment_id, created_at DESC);
