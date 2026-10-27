package io.kubespawn.platform.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.util.List;
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

@WebMvcTest(controllers = GitHubController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import(SecurityConfig.class)
@ActiveProfiles("local")
class GitHubControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GitHubCoordinator coordinator;
    @MockitoBean GitHubStore store;
    @MockitoBean AuthorizationService authorization;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000010");
    private final long repositoryId = 101010101L;

    @Test
    void projectRepositoriesRequiresProjectAccess() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "PROJECT_NOT_FOUND", "Project not found."))
            .when(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/repositories")
                .header("Authorization", TOKEN))
            .andExpect(status().isForbidden());
    }

    @Test
    void projectRepositoriesReturnsRepositoriesForProject() throws Exception {
        var repo = new BuildModels.Repository(repositoryId, tenantId, projectId, 1001L, "acme/web-frontend",
            "main", 10001L, ".github/workflows/preview.yml", "sha256", "ghcr.io/acme/api", "ghcr.io/acme/web",
            "chartRev", "templateHash", 1L, true);

        when(store.projectRepositories(tenantId, projectId)).thenReturn(List.of(repo));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/repositories")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("acme/web-frontend"))
            .andExpect(jsonPath("$[0].branch").value("main"))
            .andExpect(jsonPath("$[0].policyVersion").value(1));

        verify(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));
    }

    @Test
    void enrollProjectRepositoryRequiresPortalAdmin() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "INSUFFICIENT_ROLE", "Action requires PORTAL_ADMIN."))
            .when(authorization).requireRole(any(), eq(tenantId), eq("PORTAL_ADMIN"));

        String payload = """
            {
                "repositoryId": 101010101,
                "installationId": 1001,
                "repository": "acme/web-frontend",
                "branch": "main",
                "workflowPath": ".github/workflows/preview.yml",
                "workflowSha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                "apiImage": "ghcr.io/acme/api",
                "webImage": "ghcr.io/acme/web",
                "chartRevision": "0123456789abcdef0123456789abcdef01234567",
                "templateHash": "b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
            }
            """;

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/repositories")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden());
    }

    @Test
    void removeProjectRepositoryCallsStore() throws Exception {
        mvc.perform(delete("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/repositories/" + repositoryId)
                .header("Authorization", TOKEN))
            .andExpect(status().isNoContent());

        verify(authorization).requireRole(any(), eq(tenantId), eq("PORTAL_ADMIN"));
        verify(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));
        verify(store).disableProjectRepository(eq(tenantId), eq(projectId), eq(repositoryId), any());
    }

    @Test
    void updatePolicyUpdatesAllowedBranchAndWorkflow() throws Exception {
        var updatedRepo = new BuildModels.Repository(repositoryId, tenantId, projectId, 1001L, "acme/web-frontend",
            "feature-branch", 10001L, ".github/workflows/preview.yml", "sha256", "ghcr.io/acme/api", "ghcr.io/acme/web",
            "chartRev", "templateHash", 2L, true);

        when(store.updatePolicy(eq(tenantId), eq(projectId), eq(repositoryId), eq("feature-branch"), eq(".github/workflows/preview.yml"), any()))
            .thenReturn(updatedRepo);

        String payload = """
            {
                "allowedBranch": "feature-branch",
                "workflowPath": ".github/workflows/preview.yml"
            }
            """;

        mvc.perform(patch("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/policy/" + repositoryId)
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branch").value("feature-branch"))
            .andExpect(jsonPath("$.policyVersion").value(2));

        verify(authorization).requireRole(any(), eq(tenantId), eq("PORTAL_ADMIN"));
    }
}
