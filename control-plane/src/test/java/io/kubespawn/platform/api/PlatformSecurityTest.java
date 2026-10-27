package io.kubespawn.platform.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PlatformController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class PlatformSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean EnvironmentService environments;
    @MockitoBean io.kubespawn.platform.identity.AuthorizationService authorization;
    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";

    @Test
    void inventoryRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/environments")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongBearerTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/platform").header("Authorization", "Bearer incorrect"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedInventoryUsesTheDatabaseService() throws Exception {
        when(environments.list(org.mockito.ArgumentMatchers.eq(50), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/environments").header("Authorization", TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void inventoryRejectsAnUnboundedLimit() throws Exception {
        mvc.perform(get("/api/v1/environments?limit=10000").header("Authorization", TOKEN))
            .andExpect(status().isBadRequest());
    }

    @Test
    void foundationDoesNotClaimToProvisionEnvironments() throws Exception {
        mvc.perform(get("/api/v1/platform").header("Authorization", TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.controllerEnabled").value(false))
            .andExpect(jsonPath("$.workerEnabled").value(true))
            .andExpect(jsonPath("$.version").value("0.7.0"));
    }
}
