package io.kubespawn.platform.api;

import io.kubespawn.platform.domain.Environment;
import java.time.Instant;
import java.util.UUID;

public record EnvironmentSummary(UUID id, UUID tenantId, UUID projectId, String repository, String namespace, String desiredState,
        String phase, String requestedRevision, String servingRevision, Instant createdAt, Instant expiresAt,
        String requestedRef, long intentVersion) {

    public EnvironmentSummary(UUID id, String repository, String namespace, String desiredState,
            String phase, String requestedRevision, String servingRevision, Instant createdAt, Instant expiresAt,
            String requestedRef, long intentVersion) {
        this(id, null, null, repository, namespace, desiredState, phase, requestedRevision, servingRevision,
            createdAt, expiresAt, requestedRef, intentVersion);
    }

    public static EnvironmentSummary from(Environment environment) {
        return new EnvironmentSummary(environment.getId(), environment.getRepository(),
            environment.getNamespace(), environment.getDesiredState(), environment.getPhase(),
            environment.getRequestedRevision(), environment.getServingRevision(),
            environment.getCreatedAt(), environment.getExpiresAt(),
            environment.getRequestedRef(), environment.getIntentVersion());
    }
}
