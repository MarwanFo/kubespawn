package io.kubespawn.platform.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("kubespawn.worker")
public record WorkerSettings(
        @DefaultValue("PT30S") Duration lease,
        @DefaultValue("PT5S") Duration heartbeat,
        @DefaultValue("PT20S") Duration timeout,
        @DefaultValue("PT30S") Duration prerequisiteDelay,
        @DefaultValue("PT1S") Duration retryBase,
        @DefaultValue("PT1M") Duration retryMax,
        @DefaultValue("5") int maxFailures) {
    public WorkerSettings {
        for (Duration value : new Duration[] {lease, heartbeat, timeout, prerequisiteDelay, retryBase, retryMax}) {
            if (value == null || value.toMillis() < 1) throw new IllegalArgumentException("Worker durations must be positive.");
        }
        if (heartbeat.compareTo(lease.dividedBy(2)) >= 0 || retryBase.compareTo(retryMax) > 0 || maxFailures < 1) {
            throw new IllegalArgumentException("Invalid worker heartbeat, retry bounds, or failure budget.");
        }
    }
}
