package io.kubespawn.platform.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.CreateEnvironmentRequest;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.lifecycle.LifecycleService;
import io.kubespawn.platform.worker.JobStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LifecycleController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import({SecurityConfig.class, ApiErrors.class})
@ActiveProfiles("local")
class LifecycleAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean LifecycleService lifecycle;
    @MockitoBean JobStore jobs;
    @MockitoBean AuthorizationService authorization;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private final UUID envId = UUID.fromString("10000000-0000-0000-0000-000000000031");
    private final UUID opId = UUID.fromString("10000000-0000-0000-0000-000000000099");

    @Test
    void developerCanCreateScopedEnvironment() throws Exception {
        var summary = new EnvironmentSummary(envId, tenantId, projectId, "acme/web-frontend",
            "ks-preview-42", "ACTIVE", "WAITING_BUILD", null, null,
            Instant.now(), Instant.now().plusSeconds(3600), "main", 0L);
        var mutation = new LifecycleService.Mutation(summary, opId);

        when(lifecycle.create(eq(tenantId), eq(projectId), any(CreateEnvironmentRequest.class), any(UUID.class), anyString(), anyString()))
            .thenReturn(mutation);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments")
                .header("Authorization", TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repository": "acme/web-frontend", "ref": "main", "ttlHours": 24}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.environment.id").value(envId.toString()))
            .andExpect(jsonPath("$.operationId").value(opId.toString()));

        verify(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));
        verify(authorization).requireRole(any(), eq(tenantId), eq("DEVELOPER"), eq("PORTAL_ADMIN"));
    }

    @Test
    void qaReviewerCannotCreateOrMutateEnvironment() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "INSUFFICIENT_ROLE", "Action requires DEVELOPER or PORTAL_ADMIN"))
            .when(authorization).requireRole(any(), eq(tenantId), eq("DEVELOPER"), eq("PORTAL_ADMIN"));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments")
                .header("Authorization", TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repository": "acme/web-frontend", "ref": "main", "ttlHours": 24}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void qaReviewerCannotExtendOrDeleteEnvironment() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "INSUFFICIENT_ROLE", "Action requires DEVELOPER or PORTAL_ADMIN"))
            .when(authorization).requireRole(any(), eq(tenantId), eq("DEVELOPER"), eq("PORTAL_ADMIN"));

        // Extend attempt
        mvc.perform(patch("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/expiry")
                .header("Authorization", TOKEN)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expiresAt": "2026-10-27T12:00:00Z"}
                    """))
            .andExpect(status().isForbidden());

        // Delete attempt
        mvc.perform(delete("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId)
                .header("Authorization", TOKEN)
                .header("If-Match", "\"1\""))
            .andExpect(status().isForbidden());
    }

    @Test
    void userWithoutProjectAccessIsDenied() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "No project access"))
            .when(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId)
                .header("Authorization", TOKEN))
            .andExpect(status().isForbidden());
    }
}
