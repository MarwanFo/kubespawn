package io.kubespawn.platform.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public final class BuildModels {
    private BuildModels() {}
    public record Enrollment(@Positive long repositoryId, @Positive long installationId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") String repository,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,254}") String branch,
        @NotBlank @Pattern(regexp="\\.github/workflows/[A-Za-z0-9_-]+\\.ya?ml") String workflowPath,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String workflowSha256,
        @NotBlank @Pattern(regexp="ghcr\\.io/[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*") String apiImage,
        @NotBlank @Pattern(regexp="ghcr\\.io/[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*") String webImage,
        @NotBlank @Pattern(regexp="[a-f0-9]{40}") String chartRevision,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String templateHash) {}
    public record Repository(long id, long installationId, String name, String branch, long workflowId,
        String workflowPath, String workflowHash, String apiImage, String webImage, String chartRevision,
        String templateHash, long policyVersion, boolean enabled) {}
    public record Source(long repositoryId, long installationId, long policyVersion, String sha, UUID buildId) {}
    public record Verified(UUID id, UUID deliveryId, long repositoryId, long policyVersion, String sourceSha,
        long runId, int runAttempt, String workflowRevision, String workflowHash, long artifactId,
        String artifactDigest, String apiImage, String apiDigest, String webImage, String webDigest,
        String chartRevision, String templateHash, String evidence, UUID requestId) {}
    public record Work(UUID deliveryId, long repositoryId, long runId, int attempt, UUID requestId, String sourceSha) {}
    public record Progress(UUID environmentId, String state, String sourceSha, Long runId, String runUrl,
        String apiImage, String apiDigest, String webImage, String webDigest) {}
}
