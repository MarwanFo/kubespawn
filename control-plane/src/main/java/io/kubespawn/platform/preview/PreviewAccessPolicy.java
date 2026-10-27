package io.kubespawn.platform.preview;

import java.security.Principal;
import java.util.UUID;

/**
 * Strategy interface governing access to private ephemeral preview environments.
 * Phase 09 establishes the interface and default denial for QA Reviewers until
 * Phase 10 supplies the review assignment records.
 */
public interface PreviewAccessPolicy {
    void requirePreviewAccess(Principal actor, UUID tenantId, UUID projectId, UUID environmentId);
}
