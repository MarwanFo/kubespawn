package io.kubespawn.platform.worker;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JobRunner {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private final String worker = "worker-" + UUID.randomUUID();
    private final JobStore store;
    private final JobProcessor processor;
    private final WorkerSettings settings;
    private final ExecutorService processors = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("job-heartbeat").factory());

    public JobRunner(JobStore store, JobProcessor processor, WorkerSettings settings) {
        this.store = store; this.processor = processor; this.settings = settings;
    }

    public boolean runOnce() {
        store.recoverExpired();
        var claimed = store.claim(worker);
        if (claimed.isEmpty()) return false;
        JobLease lease = claimed.get();
        var intent = store.prepare(lease);
        if (intent.isEmpty()) return true;
        AtomicBoolean valid = new AtomicBoolean(true);
        var task = processors.submit(() -> processor.process(lease, intent.get()));
        var heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (!store.renew(lease)) { valid.set(false); task.cancel(true); }
            } catch (RuntimeException failure) {
                // Fail closed on a lost DB connection; recovery will reclaim an expired lease.
                valid.set(false);
                task.cancel(true);
                log.warn("Job heartbeat failed; the lease will be recovered if it expires.");
            }
        }, settings.heartbeat().toMillis(), settings.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        try {
            var result = task.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (valid.get()) store.waitForPrerequisite(lease, result);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            if (valid.get()) store.fail(lease, "PROCESSING_TIMEOUT", true);
        } catch (ExecutionException failure) {
            if (valid.get()) store.fail(lease,
                failure.getCause() instanceof UnsupportedOperationException ? "UNSUPPORTED_JOB_TYPE" : "PROCESSOR_ERROR",
                !(failure.getCause() instanceof UnsupportedOperationException));
        } catch (CancellationException lostLease) {
            log.info("Job processing cancelled after losing its lease.");
        } catch (InterruptedException shutdown) {
            task.cancel(true);
            Thread.currentThread().interrupt();
        } finally {
            heartbeat.cancel(false);
        }
        return true;
    }

    @PreDestroy
    void close() {
        processors.shutdownNow();
        heartbeats.shutdownNow();
    }
}
