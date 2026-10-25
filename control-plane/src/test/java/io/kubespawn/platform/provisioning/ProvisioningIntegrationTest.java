package io.kubespawn.platform.provisioning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.kubespawn.platform.github.BuildModels.Enrollment;
import io.kubespawn.platform.github.GitHubGateway;
import io.kubespawn.platform.github.GitHubStore;
import io.kubespawn.platform.lifecycle.CreateEnvironmentRequest;
import io.kubespawn.platform.lifecycle.LifecycleService;
import io.kubespawn.platform.worker.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties={"spring.profiles.active=local",
    "KUBESPAWN_DEV_TOKEN=integration-test-token-with-at-least-32-characters",
    "kubespawn.worker.enabled=false","kubespawn.lifecycle.expiry.enabled=false","kubespawn.github.enabled=false",
    "kubespawn.kubernetes.enabled=true","kubespawn.kubernetes.kubeconfig=mocked-kubeconfig","kubespawn.kubernetes.slots=2"})
class ProvisioningIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse(
        "postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ProvisioningStore store;
    @Autowired ProvisioningCoordinator coordinator;
    @Autowired LifecycleService lifecycle;
    @Autowired GitHubStore githubStore;
    @Autowired JobStore jobs;
    @Autowired JobRunner runner;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProvisioningGateway gateway;
    @MockitoBean GitHubGateway github;
    static final String SHA="a".repeat(40);
    @BeforeEach void resetDatabase() {
        jdbc.execute("TRUNCATE environment_sources,github_repositories,audit_events,api_idempotency,outbox_events CASCADE");
        reset(gateway,github); when(github.configured()).thenReturn(true);
        githubStore.enroll(new Enrollment(101,202,"example/app","main",".github/workflows/preview-build.yaml","1".repeat(64),
            "ghcr.io/example/api","ghcr.io/example/web","b".repeat(40),"2".repeat(64)),303,"local-developer");
        when(gateway.reconcile(any(),any(),any())).thenReturn(new ProvisioningModels.Observation("ARGO_SYNC_PENDING","Waiting for Argo.",false));
        when(gateway.cleanup(anyString(),any(),any())).thenReturn(new ProvisioningModels.Observation("CLEANUP_CONFIRMED","All resources absent.",true));
    }
    UUID create(String actor) {
        var created=lifecycle.create(new CreateEnvironmentRequest("example/app","main",4),UUID.randomUUID(),actor,"test");
        UUID build=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO verified_builds(id,repository_id,source_sha,policy_version,run_id,run_attempt,workflow_revision,
            workflow_sha256,artifact_id,artifact_digest,api_image,api_digest,web_image,web_digest,chart_revision,template_hash,evidence)
            VALUES (?,101,?,1,?,1,?,?,404,?,'ghcr.io/example/api',?,'ghcr.io/example/web',?,?,?,'{}'::jsonb)
            """,build,SHA,Math.abs(UUID.randomUUID().getMostSignificantBits()),SHA,"1".repeat(64),"sha256:"+"3".repeat(64),
                "sha256:"+"4".repeat(64),"sha256:"+"5".repeat(64),"b".repeat(40),"2".repeat(64));
        jdbc.update("UPDATE environments SET verified_build_id=?,requested_revision=?,chart_revision=?,phase='QUEUED' WHERE id=?",build,SHA,"b".repeat(40),created.environment().id());
        return created.environment().id();
    }
    @Test void verifiedBuildReservesBeforeExternalWorkAndPersistsObservation() {
        UUID id=create("local-developer"); assertThat(runner.runOnce()).isTrue();
        verify(gateway).reconcile(argThat(plan->plan.environmentId().equals(id) && plan.buildId()!=null),any(),any());
        assertThat(lifecycle.get(id,"local-developer").phase()).isEqualTo("PROVISIONING");
        assertThat(store.progress("local-developer").getFirst().capacityReserved()).isTrue();
        assertThat(store.progress("local-developer").getFirst().stage()).isEqualTo("ARGO_SYNC_PENDING");
        assertThat(lifecycle.get(id,"local-developer").servingRevision()).isNull();
    }
    @Test void concurrentAdmissionsCannotExceedClusterSlots() throws Exception {
        List<JobLease> leases=new ArrayList<>();
        for(int i=0;i<3;i++) { create("local-developer"); leases.add(jobs.claim("worker-"+i).orElseThrow()); }
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=leases.stream().map(lease->executor.submit(()->store.reserve(lease))).toList();
            int admitted=0; for(var future:futures) if(future.get()) admitted++;
            assertThat(admitted).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environment_provisioning WHERE reserved",Integer.class)).isEqualTo(2);
    }
    @Test void policyRevocationAndSourceMismatchPreventProvisioning() {
        UUID id=create("local-developer"); var lease=jobs.claim("worker").orElseThrow();
        jdbc.update("UPDATE github_repositories SET enabled=false WHERE repository_id=101");
        assertThat(coordinator.reconcile(lease).reason()).isEqualTo("BUILD_VERIFICATION_REQUIRED");
        verifyNoInteractions(gateway);
        jdbc.update("UPDATE github_repositories SET enabled=true WHERE repository_id=101");
        jdbc.update("UPDATE environments SET requested_revision=? WHERE id=?","c".repeat(40),id);
        assertThat(store.plan(id)).isEmpty();
    }
    @Test void deletedIntentCannotAdmitOrCommitAnOldObservation() {
        UUID id=create("local-developer"); var lease=jobs.claim("worker").orElseThrow();
        lifecycle.delete(id,0,"local-developer","test-delete");
        assertThat(store.reserve(lease)).isFalse();
        jobs.waitForPrerequisite(lease,new JobProcessor.Decision("ARGO_SYNC_PENDING","old",null,null,null,
            new ProvisioningModels.Observation("ARGO_SYNC_PENDING","stale result",false)));
        assertThat(lifecycle.get(id,"local-developer").phase()).isEqualTo("TERMINATING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environment_provisioning",Integer.class)).isZero();
    }
    @Test void expiredLeaseCannotReserveCapacity() {
        create("local-developer"); var lease=jobs.claim("worker").orElseThrow();
        jdbc.update("UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",lease.id());
        assertThat(store.reserve(lease)).isFalse();
        verifyNoInteractions(gateway);
    }
    @Test void cleanupRetainsCapacityUntilActualAbsenceAndContinuesMonitoring() {
        UUID id=create("local-developer"); runner.runOnce(); lifecycle.delete(id,0,"local-developer","delete");
        when(gateway.cleanup(anyString(),any(),any())).thenReturn(new ProvisioningModels.Observation("NAMESPACE_CLEANUP_PENDING","Terminating.",false));
        runner.runOnce();
        assertThat(lifecycle.get(id,"local-developer").phase()).isEqualTo("TERMINATING");
        assertThat(store.progress("local-developer").getFirst().capacityReserved()).isTrue();
        when(gateway.cleanup(anyString(),any(),any())).thenReturn(new ProvisioningModels.Observation("CLEANUP_CONFIRMED","Absent.",true));
        jdbc.update("UPDATE jobs SET available_at=clock_timestamp() WHERE environment_id=?",id);
        runner.runOnce();
        assertThat(lifecycle.get(id,"local-developer").phase()).isEqualTo("DESTROYED");
        assertThat(store.progress("local-developer").getFirst().capacityReserved()).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE environment_id=? AND job_type='TEARDOWN_ENVIRONMENT'",String.class,id)).isEqualTo("WAITING");
    }
    @Test void cleanupFailuresKeepRetryingBeyondProvisioningFailureBudget() {
        UUID id=create("local-developer"); lifecycle.delete(id,0,"local-developer","delete");
        jdbc.update("UPDATE jobs SET failure_count=8 WHERE environment_id=? AND job_type='TEARDOWN_ENVIRONMENT'",id);
        when(gateway.cleanup(anyString(),any(),any())).thenThrow(new IllegalStateException("credentials must not be persisted"));
        runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE environment_id=? AND job_type='TEARDOWN_ENVIRONMENT'",String.class,id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM jobs WHERE environment_id=? AND job_type='TEARDOWN_ENVIRONMENT'",String.class,id)).isEqualTo("PROCESSOR_ERROR");
    }
    @Test void provisioningProgressIsOwnerScoped() {
        create("alice"); create("bob");
        assertThat(store.progress("alice")).hasSize(1); assertThat(store.progress("bob")).hasSize(1);
        assertThat(store.progress("outsider")).isEmpty();
    }
}
