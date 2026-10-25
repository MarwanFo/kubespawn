CREATE TABLE provisioning_capacity (
    cluster_id VARCHAR(100) PRIMARY KEY,
    max_slots INTEGER NOT NULL CHECK (max_slots BETWEEN 1 AND 10)
);
INSERT INTO provisioning_capacity VALUES ('local', 2);

CREATE TABLE environment_provisioning (
    environment_id UUID PRIMARY KEY REFERENCES environments(id),
    cluster_id VARCHAR(100) NOT NULL REFERENCES provisioning_capacity(cluster_id),
    reserved BOOLEAN NOT NULL DEFAULT true,
    stage VARCHAR(64) NOT NULL DEFAULT 'CAPACITY_ADMITTED',
    message VARCHAR(255) NOT NULL DEFAULT 'Capacity reserved for namespace provisioning.',
    observed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX reserved_environment_capacity ON environment_provisioning(cluster_id) WHERE reserved;
