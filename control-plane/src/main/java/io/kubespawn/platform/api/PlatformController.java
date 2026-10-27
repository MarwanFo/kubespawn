package io.kubespawn.platform.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
    private final EnvironmentService environments;
    private final Environment configuration;
    private final io.kubespawn.platform.identity.AuthorizationService authorization;

    public PlatformController(EnvironmentService environments, Environment configuration,
            io.kubespawn.platform.identity.AuthorizationService authorization) {
        this.environments = environments;
        this.configuration = configuration;
        this.authorization = authorization;
    }

    @GetMapping("/platform")
    public PlatformStatus platform() {
        boolean local = Arrays.asList(configuration.getActiveProfiles()).contains("local");
        boolean saas = Arrays.asList(configuration.getActiveProfiles()).contains("saas");
        var capabilities = local
            ? List.of("identity", "profiles", "tenancy", "platform-admin", "environment-inventory", "lifecycle-intents", "audit-history", "expiry-intents", "job-processing", "github-builds", "kubernetes-provisioning", "private-preview-routing", "health-probes", "prometheus")
            : List.of("identity", "profiles", "tenancy", "platform-admin", "environment-inventory", "health-probes", "prometheus");
        boolean worker = local && configuration.getProperty("kubespawn.worker.enabled", Boolean.class, true);
        boolean github=configuration.getProperty("kubespawn.github.enabled",Boolean.class,false);
        boolean controller=local && configuration.getProperty("kubespawn.kubernetes.enabled",Boolean.class,false);
        return new PlatformStatus("KubeSpawn", "0.7.0", local ? "local" : saas ? "saas" : "oidc", controller, worker, github,
            capabilities, "Complete tenant-authorized lifecycle mutations and independent image-signature admission");
    }

    @GetMapping("/environments")
    public EnvironmentPage environments(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            Principal principal) {
        return new EnvironmentPage(environments.list(limit, principal));
    }

    @GetMapping("/tenants/{tenantId}/projects/{projectId}/environments")
    public EnvironmentPage projectEnvironments(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID tenantId,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID projectId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            Principal principal) {
        authorization.requireProjectAccess(principal, tenantId, projectId);
        return new EnvironmentPage(environments.listForProject(tenantId, projectId, limit, principal));
    }

    public record PlatformStatus(String name, String version, String mode, boolean controllerEnabled,
            boolean workerEnabled, boolean githubConfigured, List<String> capabilities, String nextMilestone) {}
    public record EnvironmentPage(List<EnvironmentSummary> items) {}
}
