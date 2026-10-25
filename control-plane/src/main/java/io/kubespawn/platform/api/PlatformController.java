package io.kubespawn.platform.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    public PlatformController(EnvironmentService environments, Environment configuration) {
        this.environments = environments;
        this.configuration = configuration;
    }

    @GetMapping("/platform")
    public PlatformStatus platform() {
        boolean local = Arrays.asList(configuration.getActiveProfiles()).contains("local");
        var capabilities = local
            ? List.of("environment-inventory", "lifecycle-intents", "audit-history", "expiry-intents", "job-processing", "github-builds", "kubernetes-provisioning", "private-preview-routing", "health-probes", "prometheus")
            : List.of("environment-inventory", "health-probes", "prometheus");
        boolean worker = local && configuration.getProperty("kubespawn.worker.enabled", Boolean.class, true);
        boolean github=configuration.getProperty("kubespawn.github.enabled",Boolean.class,false);
        boolean controller=local && configuration.getProperty("kubespawn.kubernetes.enabled",Boolean.class,false);
        return new PlatformStatus("KubeSpawn", "0.6.0", local ? "local" : "oidc", controller, worker, github,
            capabilities, "Add independent image-signature admission and production identity");
    }

    @GetMapping("/environments")
    public EnvironmentPage environments(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return new EnvironmentPage(environments.list(limit));
    }

    public record PlatformStatus(String name, String version, String mode, boolean controllerEnabled,
            boolean workerEnabled, boolean githubConfigured, List<String> capabilities, String nextMilestone) {}
    public record EnvironmentPage(List<EnvironmentSummary> items) {}
}
