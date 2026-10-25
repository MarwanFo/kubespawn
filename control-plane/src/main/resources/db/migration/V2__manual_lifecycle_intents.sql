ALTER TABLE environments ADD COLUMN owner_actor VARCHAR(255) NOT NULL DEFAULT 'legacy-unowned';
ALTER TABLE environments ADD COLUMN requested_ref VARCHAR(255) NOT NULL DEFAULT 'main';
CREATE INDEX environments_owner ON environments(owner_actor, created_at DESC);

CREATE TABLE api_idempotency (
    actor VARCHAR(255) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    environment_id UUID REFERENCES environments(id),
    operation_id UUID REFERENCES jobs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor, idempotency_key),
    CHECK ((environment_id IS NULL) = (operation_id IS NULL))
);
-- Claims are completed inside the same transaction as environment/job/audit/outbox writes.
-- Keep records for at least the environment's entire retention window before designing pruning.
