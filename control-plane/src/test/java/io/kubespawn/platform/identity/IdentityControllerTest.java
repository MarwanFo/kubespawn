package io.kubespawn.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=IdentityController.class,
    properties="KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class IdentityControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean IdentityService identities;
    @MockitoBean AuthorizationService authorization;
    private static final String TOKEN="Bearer test-local-token-with-at-least-32-characters";

    @Test void profileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test void authenticatedProfileContainsTenantAndRole() throws Exception {
        var membership=new IdentityService.Membership(UUID.randomUUID(),"local","Local workspace","TENANT_ADMIN","ACTIVE");
        when(identities.current(any(org.springframework.security.core.Authentication.class))).thenReturn(
            new IdentityService.Profile(UUID.randomUUID(),null,"Local Administrator",null,"ACTIVE",true,"local",List.of(membership)));
        mvc.perform(get("/api/v1/me").header("Authorization",TOKEN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Local Administrator"))
            .andExpect(jsonPath("$.platformAdmin").value(true))
            .andExpect(jsonPath("$.memberships[0].role").value("TENANT_ADMIN"));
    }

    @Test void permissionsReturnsEffectivePermissions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(authorization.getEffectivePermissions(any(), any())).thenReturn(
            new AuthorizationService.EffectivePermissions(tenantId, "PORTAL_ADMIN", true, true, true, true, true));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/permissions").header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("PORTAL_ADMIN"))
            .andExpect(jsonPath("$.canSpawnPreviews").value(true))
            .andExpect(jsonPath("$.canManageOrganization").value(true));
    }

    @Test void adminOverviewRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test void membersReturnsTenantMembers() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(identities.listMembers(tenantId)).thenReturn(List.of(
            new IdentityService.TenantMember(UUID.randomUUID(), "dev@test.test", "Dave Dev", null, "DEVELOPER", "ACTIVE",
                java.time.Instant.now(), java.time.Instant.now())
        ));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/members").header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("dev@test.test"))
            .andExpect(jsonPath("$[0].role").value("DEVELOPER"));
    }

    @Test void invitationsReturnsTenantInvitations() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(identities.listInvitations(tenantId)).thenReturn(List.of(
            new IdentityService.Invitation(UUID.randomUUID(), tenantId, "invite@test.test", "QA_REVIEWER", "PENDING",
                java.time.Instant.now().plusSeconds(3600), java.time.Instant.now(), null)
        ));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/invitations").header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("invite@test.test"))
            .andExpect(jsonPath("$[0].role").value("QA_REVIEWER"));
    }

    @Test void sessionsReturnsCurrentAndOtherSessions() throws Exception {
        when(identities.listSessions(any(), any())).thenReturn(List.of(
            new IdentityService.SessionSummary("sess-1", java.time.Instant.now(), java.time.Instant.now(), true)
        ));
        mvc.perform(get("/api/v1/me/sessions").header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sessionId").value("sess-1"))
            .andExpect(jsonPath("$[0].current").value(true));
    }
}
