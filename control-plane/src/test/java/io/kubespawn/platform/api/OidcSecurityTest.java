package io.kubespawn.platform.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PlatformController.class, properties = {
    "OIDC_ISSUER_URI=https://issuer.example.test", "KUBESPAWN_DEV_TOKEN=local-token-not-accepted-in-oidc-mode"
})
@Import(SecurityConfig.class)
class OidcSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean EnvironmentService environments;
    @MockitoBean JwtDecoder decoder;

    @Test
    void noTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/environments")).andExpect(status().isUnauthorized());
    }

    @Test
    void aJwtWithoutReadScopeIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/environments").with(jwt().authorities(List.of())))
            .andExpect(status().isForbidden());
    }

    @Test
    void aJwtWithReadScopeCanReadInventory() throws Exception {
        when(environments.list(org.mockito.ArgumentMatchers.eq(50), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/environments").with(jwt().authorities(
            new SimpleGrantedAuthority("SCOPE_environments:read"))))
            .andExpect(status().isOk());
    }

    @Test
    void developmentBearerDoesNotBypassOidc() throws Exception {
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Not a signed JWT"));
        mvc.perform(get("/api/v1/platform").header("Authorization",
            "Bearer local-token-not-accepted-in-oidc-mode"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void lifecycleMutationsRemainDisabledOutsideLocalMode() throws Exception {
        mvc.perform(post("/api/v1/environments").with(jwt().authorities(
            new SimpleGrantedAuthority("SCOPE_environments:read"),
            new SimpleGrantedAuthority("SCOPE_environments:write"))))
            .andExpect(status().isForbidden());
    }
}
