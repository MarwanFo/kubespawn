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

    @Test void adminOverviewRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
    }
}
