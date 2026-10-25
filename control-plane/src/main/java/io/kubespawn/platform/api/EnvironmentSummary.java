package io.kubespawn.platform.api;

import io.kubespawn.platform.domain.Environment;
import java.time.Instant;
import java.util.UUID;

public record EnvironmentSummary(UUID id, String repository, String namespace, String desiredState,
        String phase, String requestedRevision, String servingRevision, Instant createdAt, Instant expiresAt) {
    public static EnvironmentSummary from(Environment environment) {
        return new EnvironmentSummary(environment.getId(), environment.getRepository(),
            environment.getNamespace(), environment.getDesiredState(), environment.getPhase(),
            environment.getRequestedRevision(), environment.getServingRevision(),
            environment.getCreatedAt(), environment.getExpiresAt());
    }
}
