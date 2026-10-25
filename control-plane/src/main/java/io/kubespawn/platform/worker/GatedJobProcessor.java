package io.kubespawn.platform.worker;

import org.springframework.stereotype.Component;
import io.kubespawn.platform.github.GitHubCoordinator;

@Component
public class GatedJobProcessor implements JobProcessor {
    private final GitHubCoordinator github;
    public GatedJobProcessor(GitHubCoordinator github) { this.github=github; }
    @Override
    public Decision process(JobLease job, Intent intent) {
        return switch (job.type()) {
            case "RECONCILE_ENVIRONMENT" -> github.reconcile(job,intent);
            case "VERIFY_GITHUB_BUILD", "DISPATCH_GITHUB_BUILD" -> github.process(job);
            case "TEARDOWN_ENVIRONMENT" -> new Decision("CLEANUP_VERIFICATION_REQUIRED",
                "Waiting for the cleanup integration to verify resource removal.");
            default -> throw new UnsupportedOperationException("Unsupported job type.");
        };
    }
}
