package io.kubespawn.platform.identity;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {
    private final IdentityService identities;
    private final JdbcTemplate jdbc;

    public AuthorizationService(IdentityService identities, JdbcTemplate jdbc) {
        this.identities = identities;
        this.jdbc = jdbc;
    }

    public record EffectivePermissions(
        UUID tenantId,
        String role,
        boolean canSpawnPreviews,
        boolean canReviewPreviews,
        boolean canManageOrganization,
        boolean canManageProjects,
        boolean canAdministerQuotas
    ) {}

    @Transactional(readOnly = true)
    public EffectivePermissions getEffectivePermissions(Principal principal, UUID tenantId) {
        IdentityService.Profile profile = identities.current(principal);
        if (profile.platformAdmin()) {
            return new EffectivePermissions(tenantId, "PORTAL_ADMIN", true, true, true, true, true);
        }

        var membership = profile.memberships().stream()
            .filter(m -> m.tenantId().equals(tenantId) && "ACTIVE".equals(m.status()))
            .findFirst()
            .orElseThrow(() -> denied("TENANT_ACCESS_DENIED", "You do not have access to this organization."));

        String role = normalizeRole(membership.role());
        return switch (role) {
            case "PORTAL_ADMIN" -> new EffectivePermissions(tenantId, role, true, true, true, true, true);
            case "QA_REVIEWER" -> new EffectivePermissions(tenantId, role, false, true, false, false, false);
            case "DEVELOPER" -> new EffectivePermissions(tenantId, role, true, false, false, false, false);
            default -> new EffectivePermissions(tenantId, role, false, false, false, false, false);
        };
    }

    @Transactional(readOnly = true)
    public void requireRole(Principal principal, UUID tenantId, String... allowedRoles) {
        IdentityService.Profile profile = identities.current(principal);
        if (profile.platformAdmin()) {
            return;
        }

        var membership = profile.memberships().stream()
            .filter(m -> m.tenantId().equals(tenantId) && "ACTIVE".equals(m.status()))
            .findFirst()
            .orElseThrow(() -> denied("TENANT_ACCESS_DENIED", "You do not have access to this organization."));

        String userRole = normalizeRole(membership.role());
        boolean matched = Arrays.stream(allowedRoles)
            .map(this::normalizeRole)
            .anyMatch(r -> r.equals(userRole));

        if (!matched) {
            throw denied("INSUFFICIENT_ROLE", "Action requires one of: " + Arrays.toString(allowedRoles));
        }
    }

    @Transactional(readOnly = true)
    public void requireTenantAccess(Principal principal, UUID tenantId) {
        requireRole(principal, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
    }


    @Transactional(readOnly = true)
    public void requireProjectAccess(Principal principal, UUID tenantId, UUID projectId) {
        IdentityService.Profile profile = identities.current(principal);
        if (profile.platformAdmin()) {
            return;
        }

        // Must be active member of tenant
        requireRole(principal, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");

        // Verify project belongs to tenant
        Boolean projectInTenant = jdbc.queryForObject(
            "SELECT count(*) > 0 FROM projects WHERE id=? AND tenant_id=? AND status='ACTIVE'",
            Boolean.class, projectId, tenantId);
        if (!Boolean.TRUE.equals(projectInTenant)) {
            throw denied("PROJECT_NOT_FOUND", "Project does not exist or does not belong to this organization.");
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return "DEVELOPER";
        return switch (role.toUpperCase()) {
            case "PORTAL_ADMIN", "TENANT_ADMIN" -> "PORTAL_ADMIN";
            case "QA_REVIEWER", "VIEWER" -> "QA_REVIEWER";
            default -> "DEVELOPER";
        };
    }

    private static LifecycleException denied(String code, String message) {
        return new LifecycleException(HttpStatus.FORBIDDEN, code, message);
    }
}
