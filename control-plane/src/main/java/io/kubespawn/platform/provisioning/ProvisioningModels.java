package io.kubespawn.platform.provisioning;

import java.util.UUID;

public final class ProvisioningModels {
    private ProvisioningModels() {}
    public record Plan(UUID environmentId, String namespace, String repository, String sourceSha,
            String chartRevision, String templateHash, String apiImage, String apiDigest,
            String webImage, String webDigest, UUID buildId, long policyVersion) {}
    public record Observation(String stage, String message, boolean removed, boolean ready, String servingRevision) {
        public Observation(String stage, String message, boolean removed) { this(stage,message,removed,false,null); }
    }
    public record Progress(UUID environmentId, String stage, String message, String namespace,
            String application, boolean capacityReserved, String observedAt, String previewUrl) {}
}
