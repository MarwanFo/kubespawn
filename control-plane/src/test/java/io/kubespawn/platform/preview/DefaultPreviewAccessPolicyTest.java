package io.kubespawn.platform.preview;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DefaultPreviewAccessPolicyTest {

    @Mock AuthorizationService authorization;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Principal principal;

    private DefaultPreviewAccessPolicy policy;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policy = new DefaultPreviewAccessPolicy(authorization, jdbcTemplate);
    }

    @Test
    void developerOrAdminAllowedDirectly() {
        when(authorization.getEffectivePermissions(principal, tenantId))
            .thenReturn(new AuthorizationService.EffectivePermissions(tenantId, "DEVELOPER", true, false, false, false, false));

        assertThatCode(() -> policy.requirePreviewAccess(principal, tenantId, projectId, envId))
            .doesNotThrowAnyException();

        verify(authorization).requireProjectAccess(principal, tenantId, projectId);
        verify(authorization).requireRole(principal, tenantId, "DEVELOPER", "PORTAL_ADMIN");
    }

    @Test
    void qaReviewerWithActiveAssignmentIsAllowed() {
        when(principal.getName()).thenReturn("test-user");
        when(authorization.getEffectivePermissions(principal, tenantId))
            .thenReturn(new AuthorizationService.EffectivePermissions(tenantId, "QA_REVIEWER", false, true, false, false, false));

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(envId), eq(tenantId), eq(projectId), eq("test-user")))
            .thenReturn(true);

        assertThatCode(() -> policy.requirePreviewAccess(principal, tenantId, projectId, envId))
            .doesNotThrowAnyException();
    }

    @Test
    void qaReviewerWithoutActiveAssignmentIsDenied() {
        when(principal.getName()).thenReturn("test-user");
        when(authorization.getEffectivePermissions(principal, tenantId))
            .thenReturn(new AuthorizationService.EffectivePermissions(tenantId, "QA_REVIEWER", false, true, false, false, false));

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(envId), eq(tenantId), eq(projectId), eq("test-user")))
            .thenReturn(false);

        assertThatThrownBy(() -> policy.requirePreviewAccess(principal, tenantId, projectId, envId))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.FORBIDDEN)
            .hasMessageContaining("active review assignment");
    }
}
