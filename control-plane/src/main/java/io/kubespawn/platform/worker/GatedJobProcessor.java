package io.kubespawn.platform.worker;

import org.springframework.stereotype.Component;
import io.kubespawn.platform.github.GitHubCoordinator;

@Component
public class GatedJobProcessor implements JobProcessor {
    private final GitHubCoordinator github;
    private final io.kubespawn.platform.provisioning.ProvisioningCoordinator provisioning;
    public GatedJobProcessor(GitHubCoordinator github,io.kubespawn.platform.provisioning.ProvisioningCoordinator provisioning) { this.github=github; this.provisioning=provisioning; }
    @Override
    public Decision process(JobLease job, Intent intent) {
        return switch (job.type()) {
            case "RECONCILE_ENVIRONMENT" -> {
                // Persist a newly selected build under the worker fence before external provisioning.
                yield github.configured() && provisioning.hasVerifiedBuild(job) ? provisioning.reconcile(job) : github.reconcile(job,intent);
            }
            case "VERIFY_GITHUB_BUILD", "DISPATCH_GITHUB_BUILD" -> github.process(job);
            case "TEARDOWN_ENVIRONMENT" -> provisioning.cleanup(job);
            default -> throw new UnsupportedOperationException("Unsupported job type.");
        };
    }
}
