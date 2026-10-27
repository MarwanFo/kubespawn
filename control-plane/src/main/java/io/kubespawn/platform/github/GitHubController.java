package io.kubespawn.platform.github;

import io.kubespawn.platform.identity.AuthorizationService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitHubController {
    private final GitHubCoordinator coordinator;
    private final GitHubStore store;
    private final AuthorizationService authorization;

    public GitHubController(GitHubCoordinator coordinator, GitHubStore store, AuthorizationService authorization) {
        this.coordinator = coordinator;
        this.store = store;
        this.authorization = authorization;
    }

    // Legacy / Local routes
    @GetMapping("/api/v1/github/status")
    public Map<String, Object> status() {
        return Map.of("configured", coordinator.configured(), "repositories", store.repositories());
    }

    @PostMapping("/api/v1/github/repositories")
    public BuildModels.Repository enroll(@Valid @RequestBody BuildModels.Enrollment body, Principal actor) {
        return coordinator.enroll(body, actor.getName());
    }

    @GetMapping("/api/v1/github/environment-builds")
    public List<BuildModels.Progress> progress(Principal actor) {
        return store.progress(actor.getName(), coordinator.configured());
    }

    // Phase 07: Scoped Project Repository & Policy routes
    @GetMapping("/api/v1/tenants/{tenantId}/projects/{projectId}/repositories")
    public List<BuildModels.Repository> projectRepositories(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, Principal principal) {
        authorization.requireProjectAccess(principal, tenantId, projectId);
        return store.projectRepositories(tenantId, projectId);
    }

    @PostMapping("/api/v1/tenants/{tenantId}/projects/{projectId}/repositories")
    public BuildModels.Repository enrollProjectRepository(
            @PathVariable UUID tenantId, @PathVariable UUID projectId,
            @Valid @RequestBody BuildModels.Enrollment body, Principal principal) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        authorization.requireProjectAccess(principal, tenantId, projectId);
        return coordinator.enroll(tenantId, projectId, body, principal.getName());
    }

    @DeleteMapping("/api/v1/tenants/{tenantId}/projects/{projectId}/repositories/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeProjectRepository(
            @PathVariable UUID tenantId, @PathVariable UUID projectId,
            @PathVariable long repositoryId, Principal principal) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        authorization.requireProjectAccess(principal, tenantId, projectId);
        store.disableProjectRepository(tenantId, projectId, repositoryId, principal.getName());
    }

    @PatchMapping("/api/v1/tenants/{tenantId}/projects/{projectId}/policy/{repositoryId}")
    public BuildModels.Repository updatePolicy(
            @PathVariable UUID tenantId, @PathVariable UUID projectId,
            @PathVariable long repositoryId,
            @Valid @RequestBody BuildModels.PolicyUpdate body, Principal principal) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        authorization.requireProjectAccess(principal, tenantId, projectId);
        return store.updatePolicy(tenantId, projectId, repositoryId, body.allowedBranch(), body.workflowPath(), principal.getName());
    }
}
