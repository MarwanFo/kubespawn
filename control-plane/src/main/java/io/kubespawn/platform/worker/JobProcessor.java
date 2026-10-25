package io.kubespawn.platform.worker;

import io.kubespawn.platform.github.BuildModels;
import java.util.UUID;

public interface JobProcessor {
    Decision process(JobLease job, Intent intent) throws Exception;

    record Intent(long version, String desiredState, String phase, String requestedRevision, String repository, String requestedRef) {}
    record Decision(String reason, String message, BuildModels.Source source, BuildModels.Verified verified, UUID dispatchedRequestId) {
        public Decision(String reason,String message) { this(reason,message,null,null,null); }
    }
}
