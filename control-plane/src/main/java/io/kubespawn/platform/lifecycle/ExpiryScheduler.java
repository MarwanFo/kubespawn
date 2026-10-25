package io.kubespawn.platform.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("local")
@EnableScheduling
@ConditionalOnProperty(name = "kubespawn.lifecycle.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryScheduler {
    private final LifecycleService lifecycle;

    public ExpiryScheduler(LifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @Scheduled(fixedDelayString = "${kubespawn.lifecycle.expiry.interval:PT30S}")
    public void requestExpiredDeletion() { lifecycle.expireDueBatch(); }
}
