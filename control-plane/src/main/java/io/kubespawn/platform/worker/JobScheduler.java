package io.kubespawn.platform.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("local")
@EnableScheduling
@ConditionalOnProperty(name="kubespawn.worker.enabled", havingValue="true", matchIfMissing=true)
public class JobScheduler {
    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);
    private final JobRunner runner;
    public JobScheduler(JobRunner runner) { this.runner = runner; }

    @Scheduled(fixedDelayString="${kubespawn.worker.poll-interval:PT2S}")
    public void poll() {
        try { runner.runOnce(); }
        catch (RuntimeException failure) { log.warn("Job poll failed; durable jobs will be retried on a later poll."); }
    }
}
