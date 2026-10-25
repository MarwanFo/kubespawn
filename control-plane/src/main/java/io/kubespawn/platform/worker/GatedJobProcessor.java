package io.kubespawn.platform.worker;

import org.springframework.stereotype.Component;

@Component
public class GatedJobProcessor implements JobProcessor {
    @Override
    public Decision process(JobLease job, Intent intent) {
        // A submitted ref or SHA is not trusted build evidence. No external adapters exist yet.
        return switch (job.type()) {
            case "RECONCILE_ENVIRONMENT" -> new Decision("BUILD_VERIFICATION_REQUIRED",
                "Waiting for trusted build verification before provisioning.");
            case "TEARDOWN_ENVIRONMENT" -> new Decision("CLEANUP_VERIFICATION_REQUIRED",
                "Waiting for the cleanup integration to verify resource removal.");
            default -> throw new UnsupportedOperationException("Unsupported job type.");
        };
    }
}
