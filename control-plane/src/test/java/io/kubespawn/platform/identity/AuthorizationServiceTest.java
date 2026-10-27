package io.kubespawn.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthorizationServiceTest {
    private IdentityService identities;
    private JdbcTemplate jdbc;
    private AuthorizationService auth;
    private Principal principal;

    private final UUID tenantAcme = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID tenantGlobex = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID projectFrontend = UUID.fromString("10000000-0000-0000-0000-000000000010");

    @BeforeEach
    void setUp() {
        identities = mock(IdentityService.class);
        jdbc = mock(JdbcTemplate.class);
        auth = new AuthorizationService(identities, jdbc);
        principal = mock(Principal.class);
    }

    private IdentityService.Profile userWithMembership(String role, String status, UUID tenantId) {
        var membership = new IdentityService.Membership(tenantId, "test-tenant", "Test Workspace", role, status);
        return new IdentityService.Profile(UUID.randomUUID(), "test@example.com", "Test User", null, "ACTIVE", false, "test-tenant", List.of(membership));
    }

    @Test
    void platformAdminHasFullPermissionsOnAnyTenant() {
        var profile = new IdentityService.Profile(UUID.randomUUID(), "root@kubespawn.test", "Root Admin", null, "ACTIVE", true, "local", List.of());
        when(identities.current(principal)).thenReturn(profile);

        var perms = auth.getEffectivePermissions(principal, tenantAcme);
        assertThat(perms.role()).isEqualTo("PORTAL_ADMIN");
        assertThat(perms.canSpawnPreviews()).isTrue();
        assertThat(perms.canReviewPreviews()).isTrue();
        assertThat(perms.canManageOrganization()).isTrue();
        assertThat(perms.canManageProjects()).isTrue();
        assertThat(perms.canAdministerQuotas()).isTrue();

        auth.requireRole(principal, tenantAcme, "DEVELOPER");
        auth.requireProjectAccess(principal, tenantAcme, projectFrontend);
    }

    @Test
    void portalAdminHasCorrectPermissions() {
        when(identities.current(principal)).thenReturn(userWithMembership("PORTAL_ADMIN", "ACTIVE", tenantAcme));

        var perms = auth.getEffectivePermissions(principal, tenantAcme);
        assertThat(perms.role()).isEqualTo("PORTAL_ADMIN");
        assertThat(perms.canSpawnPreviews()).isTrue();
        assertThat(perms.canReviewPreviews()).isTrue();
        assertThat(perms.canManageOrganization()).isTrue();
        assertThat(perms.canManageProjects()).isTrue();
        assertThat(perms.canAdministerQuotas()).isTrue();

        auth.requireRole(principal, tenantAcme, "PORTAL_ADMIN");
    }

    @Test
    void developerHasSpawnPermissionsOnly() {
        when(identities.current(principal)).thenReturn(userWithMembership("DEVELOPER", "ACTIVE", tenantAcme));

        var perms = auth.getEffectivePermissions(principal, tenantAcme);
        assertThat(perms.role()).isEqualTo("DEVELOPER");
        assertThat(perms.canSpawnPreviews()).isTrue();
        assertThat(perms.canReviewPreviews()).isFalse();
        assertThat(perms.canManageOrganization()).isFalse();
        assertThat(perms.canManageProjects()).isFalse();

        auth.requireRole(principal, tenantAcme, "DEVELOPER");
        assertThatThrownBy(() -> auth.requireRole(principal, tenantAcme, "PORTAL_ADMIN"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);
    }

    @Test
    void qaReviewerHasReviewPermissionsOnly() {
        when(identities.current(principal)).thenReturn(userWithMembership("QA_REVIEWER", "ACTIVE", tenantAcme));

        var perms = auth.getEffectivePermissions(principal, tenantAcme);
        assertThat(perms.role()).isEqualTo("QA_REVIEWER");
        assertThat(perms.canSpawnPreviews()).isFalse();
        assertThat(perms.canReviewPreviews()).isTrue();
        assertThat(perms.canManageOrganization()).isFalse();

        auth.requireRole(principal, tenantAcme, "QA_REVIEWER");
        assertThatThrownBy(() -> auth.requireRole(principal, tenantAcme, "DEVELOPER"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);
    }

    @Test
    void crossTenantAccessIsForbidden() {
        // User belongs to tenantAcme, attempts to access tenantGlobex
        when(identities.current(principal)).thenReturn(userWithMembership("DEVELOPER", "ACTIVE", tenantAcme));

        assertThatThrownBy(() -> auth.getEffectivePermissions(principal, tenantGlobex))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> auth.requireRole(principal, tenantGlobex, "DEVELOPER"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);
    }

    @Test
    void suspendedMembershipIsDenied() {
        when(identities.current(principal)).thenReturn(userWithMembership("DEVELOPER", "SUSPENDED", tenantAcme));

        assertThatThrownBy(() -> auth.getEffectivePermissions(principal, tenantAcme))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> auth.requireRole(principal, tenantAcme, "DEVELOPER"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);
    }

    @Test
    void projectAccessEnforcesProjectBelongsToTenant() {
        when(identities.current(principal)).thenReturn(userWithMembership("DEVELOPER", "ACTIVE", tenantAcme));

        // Project belongs to tenant
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(projectFrontend), eq(tenantAcme)))
            .thenReturn(true);
        auth.requireProjectAccess(principal, tenantAcme, projectFrontend);

        // Project does not belong to tenant
        UUID unknownProject = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(unknownProject), eq(tenantAcme)))
            .thenReturn(false);

        assertThatThrownBy(() -> auth.requireProjectAccess(principal, tenantAcme, unknownProject))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN);
    }
}
