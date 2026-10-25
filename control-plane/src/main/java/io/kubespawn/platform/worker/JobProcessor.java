package io.kubespawn.platform.worker;

public interface JobProcessor {
    Decision process(JobLease job, Intent intent) throws Exception;

    record Intent(long version, String desiredState, String phase, String requestedRevision) {}
    record Decision(String reason, String message) {}
}
