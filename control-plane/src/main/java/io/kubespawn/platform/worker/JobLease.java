package io.kubespawn.platform.worker;

import java.util.UUID;

public record JobLease(UUID id, UUID environmentId, String type, long intentVersion,
        String workerId, long jobToken, long environmentToken) {}
