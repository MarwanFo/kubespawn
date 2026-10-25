package io.kubespawn.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.lifecycle.CreateEnvironmentRequest;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.lifecycle.LifecycleService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties={
    "spring.profiles.active=local", "KUBESPAWN_DEV_TOKEN=integration-test-token-with-at-least-32-characters",
    "kubespawn.lifecycle.expiry.enabled=false", "kubespawn.worker.enabled=false", "kubespawn.github.enabled=false",
    "kubespawn.worker.lease=PT3S", "kubespawn.worker.heartbeat=PT0.1S", "kubespawn.worker.timeout=PT2S"
})
@AutoConfigureMockMvc
class WorkerIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
            .asCompatibleSubstituteFor("postgres"));
    static final String ACTOR = "worker-test";
    static final String TOKEN = "Bearer integration-test-token-with-at-least-32-characters";
    @Autowired JobStore store;
    @Autowired JobRunner runner;
    @Autowired LifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean JobProcessor processor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void isolateFixtures() throws Exception {
        jdbc.execute("TRUNCATE api_idempotency, environment_work_leases, jobs, audit_events, outbox_events, environments, environment_sources CASCADE");
        reset(processor);
        when(processor.process(any(), any())).thenAnswer(call -> {
            JobLease lease=call.getArgument(0);
            return switch (lease.type()) {
                case "RECONCILE_ENVIRONMENT" -> waiting();
                case "TEARDOWN_ENVIRONMENT" -> new JobProcessor.Decision("CLEANUP_VERIFICATION_REQUIRED","Waiting for the cleanup integration to verify resource removal.");
                default -> throw new UnsupportedOperationException();
            };
        });
    }

    @Test
    void competingWorkersClaimOnlyOneLeaseForOneJob() throws Exception {
        create();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> claim = () -> { start.await(); return store.claim(UUID.randomUUID().toString()).isPresent(); };
            var one = pool.submit(claim);
            var two = pool.submit(claim);
            start.countDown();
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM jobs", Integer.class)).isEqualTo(1);
    }

    @Test void failedOperationRetryRequiresOwnerAndCurrentIntent() {
        var request=create(); var lease=store.claim("retry-test").orElseThrow();
        store.fail(lease,"PROCESSOR_ERROR",false);
        assertThatThrownBy(()->store.retry(lease.id(),"outsider",0,requestId())).isInstanceOf(LifecycleException.class);
        assertThatThrownBy(()->store.retry(lease.id(),ACTOR,1,requestId())).isInstanceOf(LifecycleException.class);
        var retried=store.retry(lease.id(),ACTOR,0,requestId());
        assertThat(retried.state()).isEqualTo("PENDING"); assertThat(retried.failureCount()).isZero();
        assertThat(lifecycle.get(request.environment().id(),ACTOR).intentVersion()).isZero();
        runner.runOnce();
        assertThat(store.operation(lease.id(),ACTOR).state()).isEqualTo("WAITING");
    }

    @Test void retryCannotResurrectADeletedGeneration() {
        var request=create(); var lease=store.claim("retry-test").orElseThrow(); store.fail(lease,"PROCESSOR_ERROR",false);
        lifecycle.delete(request.environment().id(),0,ACTOR,requestId());
        assertThatThrownBy(()->store.retry(lease.id(),ACTOR,0,requestId())).isInstanceOf(LifecycleException.class);
        assertThat(lifecycle.get(request.environment().id(),ACTOR).desiredState()).isEqualTo("ABSENT");
    }

    @Test
    void separateJobsForOneEnvironmentShareOneMonotonicLease() {
        var request = create();
        lifecycle.extend(request.environment().id(), 0, request.environment().expiresAt().plus(Duration.ofHours(1)), ACTOR, requestId());
        var first = store.claim("one").orElseThrow();
        assertThat(store.claim("two")).isEmpty();
        store.waitForPrerequisite(first, waiting());
        var second = store.claim("two").orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.environmentToken()).isGreaterThan(first.environmentToken());
        assertThat(store.renew(first)).isFalse();
        assertThat(store.renew(second)).isTrue();
    }

    @Test
    void differentEnvironmentsCanBeClaimedConcurrently() {
        create(); create();
        var one = store.claim("one").orElseThrow();
        var two = store.claim("two").orElseThrow();
        assertThat(one.environmentId()).isNotEqualTo(two.environmentId());
    }

    @Test
    void renewalUpdatesBothLeasesAndCannotResurrectAnExpiredLease() {
        var lease = store.claim("one");
        assertThat(lease).isEmpty();
        create();
        var job = store.claim("one").orElseThrow();
        jdbc.update("UPDATE jobs SET lease_expires_at=clock_timestamp()+interval '1 second' WHERE id=?", job.id());
        jdbc.update("UPDATE environment_work_leases SET expires_at=clock_timestamp()+interval '1 second'");
        assertThat(store.renew(job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_expires_at > clock_timestamp()+interval '2 seconds' FROM jobs WHERE id=?", Boolean.class, job.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at > clock_timestamp()+interval '2 seconds' FROM environment_work_leases", Boolean.class)).isTrue();
        expire(job);
        assertThat(store.renew(job)).isFalse();
    }

    @Test
    void crashRecoveryBacksOffAndRejectsEveryOldLeaseWrite() {
        var request = create();
        var old = store.claim("crashed").orElseThrow();
        expire(old);
        assertThat(store.recoverExpired()).isEqualTo(1);
        var recovered = operation(request);
        assertThat(recovered.state()).isEqualTo("PENDING");
        assertThat(recovered.failureCount()).isEqualTo(1);
        assertThat(recovered.reason()).isEqualTo("LEASE_EXPIRED");
        assertThat(recovered.availableAt()).isAfter(recovered.updatedAt());
        assertThat(Duration.between(recovered.updatedAt(), recovered.availableAt())).isLessThanOrEqualTo(Duration.ofSeconds(1));
        due(old.id());
        var replacement = store.claim("replacement").orElseThrow();
        assertThat(replacement.jobToken()).isGreaterThan(old.jobToken());
        assertThat(replacement.environmentToken()).isGreaterThan(old.environmentToken());
        assertThat(store.waitForPrerequisite(old, waiting())).isFalse();
        assertThat(store.fail(old, "PROCESSOR_ERROR", true)).isFalse();
        assertThat(store.renew(old)).isFalse();
        assertThat(store.prepare(old)).isEmpty();
        assertThat(operation(request).state()).isEqualTo("RUNNING");
        assertThat(store.renew(replacement)).isTrue();
    }

    @Test
    void recoveryOfAnOldJobCannotReleaseANewerEnvironmentLease() {
        var request = create();
        var old = store.claim("crashed").orElseThrow();
        expire(old);
        var deletion = lifecycle.delete(request.environment().id(), 0, ACTOR, requestId());
        var newer = store.claim("newer").orElseThrow();
        assertThat(newer.id()).isEqualTo(deletion.operationId());
        assertThat(store.recoverExpired()).isEqualTo(1);
        assertThat(operation(request).state()).isEqualTo("SUPERSEDED");
        assertThat(store.renew(newer)).isTrue();
        assertThat(jdbc.queryForObject("SELECT job_id FROM environment_work_leases", UUID.class)).isEqualTo(newer.id());
    }

    @Test
    void crashFailuresStopAtTheConfiguredBudget() {
        var request = create();
        for (int i=0; i<5; i++) {
            due(request.operationId());
            var job = store.claim("crash-" + i).orElseThrow();
            expire(job);
            store.recoverExpired();
        }
        assertThat(operation(request).state()).isEqualTo("FAILED");
        assertThat(operation(request).failureCount()).isEqualTo(5);
        assertThat(operation(request).finishedAt()).isNotNull();
        assertThat(store.claim("sixth")).isEmpty();
    }

    @Test
    void prerequisiteChecksDoNotConsumeTheFailureBudgetOrPretendToDeploy() {
        var request = create();
        // Even a populated revision is insufficient without trusted build evidence.
        jdbc.update("UPDATE environments SET requested_revision=? WHERE id=?", "a".repeat(40), request.environment().id());
        for (int i=0; i<6; i++) { due(request.operationId()); assertThat(runner.runOnce()).isTrue(); }
        var operation = operation(request);
        assertThat(operation.state()).isEqualTo("WAITING");
        assertThat(operation.reason()).isEqualTo("BUILD_VERIFICATION_REQUIRED");
        assertThat(operation.attempts()).isEqualTo(6);
        assertThat(operation.failureCount()).isZero();
        assertThat(operation.leaseExpiresAt()).isNull();
        assertThat(operation.finishedAt()).isNull();
        assertThat(lifecycle.get(request.environment().id(), ACTOR).phase()).isEqualTo("WAITING_BUILD");
    }

    @Test
    void teardownWaitsForVerificationWithoutClaimingDestruction() {
        var request = create();
        var deletion = lifecycle.delete(request.environment().id(), 0, ACTOR, requestId());
        runner.runOnce();
        assertThat(operation(deletion).state()).isEqualTo("WAITING");
        assertThat(operation(deletion).reason()).isEqualTo("CLEANUP_VERIFICATION_REQUIRED");
        assertThat(lifecycle.get(request.environment().id(), ACTOR).phase()).isEqualTo("TERMINATING");
        assertThat(jdbc.queryForObject("SELECT destroyed_at IS NULL FROM environments", Boolean.class)).isTrue();
    }

    @Test
    void aNewerIntentSupersedesAJobBeforeProcessing() {
        var request = create();
        var job = store.claim("old").orElseThrow();
        lifecycle.extend(request.environment().id(), 0, request.environment().expiresAt().plus(Duration.ofHours(1)), ACTOR, requestId());
        assertThat(store.prepare(job)).isEmpty();
        assertThat(operation(request).state()).isEqualTo("SUPERSEDED");
        assertThat(operation(request).failureCount()).isZero();
    }

    @Test
    void deletionWhileProcessorRunsSupersedesItsResult() throws Exception {
        var request = create();
        doAnswer(call -> {
            lifecycle.delete(request.environment().id(), 0, ACTOR, requestId());
            return waiting();
        }).when(processor).process(any(), any());
        runner.runOnce();
        assertThat(operation(request).state()).isEqualTo("SUPERSEDED");
        assertThat(lifecycle.get(request.environment().id(), ACTOR).desiredState()).isEqualTo("ABSENT");
        assertThat(lifecycle.get(request.environment().id(), ACTOR).intentVersion()).isEqualTo(1);
    }

    @Test
    void staleFailureDoesNotSpendTheBudgetForNewIntent() {
        var request = create();
        var job = store.claim("old").orElseThrow();
        store.prepare(job).orElseThrow();
        lifecycle.delete(request.environment().id(), 0, ACTOR, requestId());
        assertThat(store.fail(job, "PROCESSOR_ERROR", true)).isTrue();
        assertThat(operation(request).state()).isEqualTo("SUPERSEDED");
        assertThat(operation(request).failureCount()).isZero();
    }

    @Test
    void unexpectedProcessorErrorsAreRetriedWithoutLeakingExceptionDetails() throws Exception {
        var request = create();
        doThrow(new IllegalStateException("secret-password=must-not-escape")).when(processor).process(any(), any());
        runner.runOnce();
        assertThat(operation(request).state()).isEqualTo("PENDING");
        assertThat(operation(request).reason()).isEqualTo("PROCESSOR_ERROR");
        assertThat(operation(request).message()).doesNotContain("secret-password");
        assertThat(jdbc.queryForObject("SELECT last_error FROM jobs", String.class)).isEqualTo("PROCESSOR_ERROR");
    }

    @Test
    void unsupportedJobTypesFailWithoutRetrying() {
        var request = create();
        jdbc.update("UPDATE jobs SET job_type='UNKNOWN_FUTURE_TYPE'");
        runner.runOnce();
        assertThat(operation(request).state()).isEqualTo("FAILED");
        assertThat(operation(request).reason()).isEqualTo("UNSUPPORTED_JOB_TYPE");
        assertThat(store.claim("again")).isEmpty();
    }

    @Test
    void processingTimeoutCancelsWorkAndSchedulesARetry() throws Exception {
        var request = create();
        var interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            try { new CountDownLatch(1).await(); return waiting(); }
            catch (InterruptedException cancellation) { interrupted.countDown(); throw cancellation; }
        }).when(processor).process(any(), any());
        runner.runOnce();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(operation(request).state()).isEqualTo("PENDING");
        assertThat(operation(request).reason()).isEqualTo("PROCESSING_TIMEOUT");
        assertThat(operation(request).lastHeartbeatAt()).isAfter(operation(request).createdAt());
    }

    @Test
    void progressRoutesRequireAuthenticationAndRespectOwnership() throws Exception {
        var own = lifecycle.create(body(), UUID.randomUUID(), "local-developer", requestId());
        var other = create();
        runner.runOnce();
        mvc.perform(get("/api/v1/operations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operations/" + other.operationId()).header("Authorization", TOKEN))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/operations/" + own.operationId()).header("Authorization", TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.failureCount").value(0))
            .andExpect(jsonPath("$.reason").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(get("/api/v1/operations").header("Authorization", TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].environmentId").value(own.environment().id().toString()));
        assertThatThrownBy(() -> store.operation(other.operationId(), "local-developer")).isInstanceOf(LifecycleException.class);
    }

    @Test
    void latestProgressPrefersTheHighestIntentVersion() {
        var request = create();
        var deletion = lifecycle.delete(request.environment().id(), 0, ACTOR, requestId());
        assertThat(store.latestOperations(ACTOR)).extracting(JobStore.Operation::id).containsExactly(deletion.operationId());
    }

    private LifecycleService.Mutation create() { return lifecycle.create(body(), UUID.randomUUID(), ACTOR, requestId()); }
    private CreateEnvironmentRequest body() { return new CreateEnvironmentRequest("example/app", "main", 24); }
    private String requestId() { return UUID.randomUUID().toString(); }
    private JobStore.Operation operation(LifecycleService.Mutation request) { return store.operation(request.operationId(), ACTOR); }
    private JobProcessor.Decision waiting() { return new JobProcessor.Decision("BUILD_VERIFICATION_REQUIRED", "Waiting for trusted build verification before provisioning."); }
    private void due(UUID id) { jdbc.update("UPDATE jobs SET available_at=clock_timestamp()-interval '1 second' WHERE id=?", id); }
    private void expire(JobLease job) {
        jdbc.update("UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", job.id());
        jdbc.update("UPDATE environment_work_leases SET expires_at=clock_timestamp()-interval '1 second' WHERE environment_id=?", job.environmentId());
    }
}
