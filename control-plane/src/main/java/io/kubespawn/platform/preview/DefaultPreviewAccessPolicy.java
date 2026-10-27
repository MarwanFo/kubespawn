package io.kubespawn.platform.preview;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DefaultPreviewAccessPolicy implements PreviewAccessPolicy {
    private final AuthorizationService authorization;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public DefaultPreviewAccessPolicy(AuthorizationService authorization, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.authorization = authorization;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void requirePreviewAccess(Principal actor, UUID tenantId, UUID projectId, UUID environmentId) {
        // Enforce membership and active project access
        authorization.requireProjectAccess(actor, tenantId, projectId);

        // Check user's effective permissions
        var permissions = authorization.getEffectivePermissions(actor, tenantId);
        String role = permissions.role();

        if ("QA_REVIEWER".equals(role)) {
            String actorName = actor != null ? actor.getName() : "";
            Boolean hasActiveAssignment = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM review_assignments a
                    JOIN review_requests r ON r.id = a.review_request_id
                    WHERE r.environment_id = ?
                      AND r.tenant_id = ?
                      AND r.project_id = ?
                      AND a.reviewer_actor = ?
                      AND r.status = 'PENDING'
                      AND a.status = 'ASSIGNED'
                )
                """,
                Boolean.class,
                environmentId, tenantId, projectId, actorName
            );
            if (!Boolean.TRUE.equals(hasActiveAssignment)) {
                throw new LifecycleException(HttpStatus.FORBIDDEN, "QA_REVIEW_REQUIRED",
                    "QA preview access requires an active review assignment on this environment.");
            }
            return;
        }

        // Allowed for DEVELOPER and PORTAL_ADMIN
        authorization.requireRole(actor, tenantId, "DEVELOPER", "PORTAL_ADMIN");
    }
}
