package io.kubespawn.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "environments")
public class Environment {
    @Id
    private UUID id;
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;
    @Column(name = "repository_name", nullable = false)
    private String repository;
    @Column(nullable = false)
    private String namespace;
    @Column(name = "requested_ref", nullable = false)
    private String requestedRef;
    @Column(name = "desired_state", nullable = false)
    private String desiredState;
    @Column(nullable = false)
    private String phase;
    @Column(name = "requested_revision")
    private String requestedRevision;
    @Column(name = "serving_revision")
    private String servingRevision;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Version
    @Column(name = "intent_version", nullable = false)
    private long intentVersion;

    protected Environment() {}

    public UUID getId() { return id; }
    public String getRepository() { return repository; }
    public String getNamespace() { return namespace; }
    public String getRequestedRef() { return requestedRef; }
    public long getIntentVersion() { return intentVersion; }
    public String getDesiredState() { return desiredState; }
    public String getPhase() { return phase; }
    public String getRequestedRevision() { return requestedRevision; }
    public String getServingRevision() { return servingRevision; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
