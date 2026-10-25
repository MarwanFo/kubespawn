package io.kubespawn.platform.github;

import static io.kubespawn.platform.github.BuildModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.lifecycle.CreateEnvironmentRequest;
import io.kubespawn.platform.lifecycle.LifecycleService;
import io.kubespawn.platform.worker.JobProcessor;
import io.kubespawn.platform.worker.JobRunner;
import io.kubespawn.platform.worker.JobStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties={"spring.profiles.active=local",
    "KUBESPAWN_DEV_TOKEN=integration-test-token-with-at-least-32-characters",
    "kubespawn.lifecycle.expiry.enabled=false","kubespawn.worker.enabled=false","kubespawn.github.enabled=false",
    "kubespawn.github.webhook-secret=integration-webhook-secret-at-least-32-characters"})
@AutoConfigureMockMvc
class GitHubIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb").asCompatibleSubstituteFor("postgres"));
    static final String SECRET="integration-webhook-secret-at-least-32-characters";
    static final String TOKEN="Bearer integration-test-token-with-at-least-32-characters";
    static final String SHA="a".repeat(40);
    static final byte[] WORKFLOW="approved platform-owned workflow v1\n".getBytes(StandardCharsets.UTF_8);
    @Autowired GitHubStore store;
    @Autowired GitHubCoordinator coordinator;
    @Autowired GitHubWebhookService webhooks;
    @Autowired JobRunner runner;
    @Autowired JobStore jobs;
    @Autowired JobProcessor processor;
    @Autowired LifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean GitHubGateway github;
    ObjectNode run;
    ObjectNode manifest;
    String branchHead;
    boolean corruptArchive;
    boolean duplicateArtifact;
    boolean missingArtifact;
    boolean expiredArtifact;
    String archiveName;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
    }

    @BeforeEach void fixtures() throws Exception {
        jdbc.execute("TRUNCATE api_idempotency,environment_work_leases,github_build_requests,github_webhook_deliveries,verified_builds,jobs,audit_events,outbox_events,environments,environment_sources,github_repositories CASCADE");
        reset(github); when(github.configured()).thenReturn(true);
        branchHead=SHA; corruptArchive=false; duplicateArtifact=false; missingArtifact=false; expiredArtifact=false; archiveName="build-result.json";
        run=(ObjectNode)json.readTree("""
            {"id":404,"run_attempt":1,"workflow_id":303,"path":".github/workflows/preview-build.yaml",
             "head_branch":"main","event":"push","status":"completed","conclusion":"success",
             "repository":{"id":101},"head_repository":{"id":101}}
            """);
        run.put("head_sha",SHA);
        manifest=(ObjectNode)json.readTree("""
            {"schemaVersion":1,"repositoryId":101,"repository":"example/app","runId":404,"runAttempt":1,"requestId":"",
             "images":{"api":{"repository":"ghcr.io/example/app-preview-api"},"web":{"repository":"ghcr.io/example/app-preview-web"}},
             "evidence":{"scanPolicy":"trivy-high-critical-no-ignore-v1","sbom":"buildkit-spdx","provenance":"buildkit-max"}}
            """);
        manifest.put("sourceSha",SHA);
        ((ObjectNode)manifest.path("images").path("api")).put("digest","sha256:"+"1".repeat(64));
        ((ObjectNode)manifest.path("images").path("web")).put("digest","sha256:"+"2".repeat(64));
        when(github.workflow(any(),anyString())).thenReturn(WORKFLOW);
        when(github.get(any(),anyString())).thenAnswer(call -> {
            String path=call.getArgument(1);
            if (path.contains("/commits/")) return json.createObjectNode().put("sha",path.endsWith("c".repeat(40)) ? "c".repeat(40) : branchHead);
            if (path.contains("/artifacts?")) {
                byte[] archive=archive();
                var value=json.createObjectNode(); var list=value.putArray("artifacts");
                var artifact=json.createObjectNode().put("id",505).put("name","kubespawn-build-result-1")
                    .put("expired",expiredArtifact).put("size_in_bytes",archive.length).put("digest","sha256:"+GitHubCoordinator.hash(archive));
                var origin=artifact.putObject("workflow_run"); origin.put("id",404).put("repository_id",101).put("head_repository_id",101)
                    .put("head_sha",run.path("head_sha").asText());
                if (!missingArtifact) { list.add(artifact); if (duplicateArtifact) list.add(artifact.deepCopy()); }
                return value;
            }
            if (path.contains("/actions/runs/")) return run;
            if (path.contains("/actions/workflows/")) {
                assertThat(path).isEqualTo("/repos/example/app/actions/workflows/preview-build.yaml");
                return json.createObjectNode().put("id",303).put("path",".github/workflows/preview-build.yaml").put("state","active");
            }
            return json.createObjectNode().put("id",101).put("full_name","Example/App").put("fork",false);
        });
        when(github.artifact(any(),any(Long.class))).thenAnswer(call -> { byte[] bytes=archive(); if (corruptArchive) bytes[0]^=1; return bytes; });
    }

    @Test void fullManualRequestPinsDispatchesVerifiesAndQueuesWithoutDeploying() throws Exception {
        enrolled(); var request=create("local-developer");
        runner.runOnce();
        assertThat(lifecycle.get(request.environment().id(),"local-developer").requestedRevision()).isEqualTo(SHA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_build_requests",Integer.class)).isEqualTo(1);
        runner.runOnce();
        UUID requestId=jdbc.queryForObject("SELECT id FROM github_build_requests",UUID.class);
        verify(github).dispatch(any(),org.mockito.ArgumentMatchers.eq(SHA),org.mockito.ArgumentMatchers.eq(requestId.toString()));
        assertThat(jdbc.queryForObject("SELECT state FROM github_build_requests",String.class)).isEqualTo("DISPATCHED");
        queue(); runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isEqualTo(1);
        due(request.operationId()); runner.runOnce();
        var environment=lifecycle.get(request.environment().id(),"local-developer");
        assertThat(environment.phase()).isEqualTo("QUEUED"); assertThat(environment.desiredState()).isEqualTo("ACTIVE");
        assertThat(environment.servingRevision()).isNull(); assertThat(environment.intentVersion()).isZero();
        assertThat(jobs.operation(request.operationId(),"local-developer").reason()).isEqualTo("KUBERNETES_INTEGRATION_REQUIRED");
        assertThat(store.progress("local-developer",true).getFirst().state()).isEqualTo("VERIFIED");
        mvc.perform(get("/api/v1/github/environment-builds").header("Authorization",TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].sourceSha").value(SHA))
            .andExpect(jsonPath("$[0].apiDigest").value("sha256:"+"1".repeat(64)));
    }

    @Test void enrollmentChecksStableIdentityAndApprovedWorkflowBeforeSaving() {
        var result=coordinator.enroll(enrollment(),"local-developer");
        assertThat(result.id()).isEqualTo(101); assertThat(result.workflowId()).isEqualTo(303);
        assertThat(result.policyVersion()).isEqualTo(1);
        when(github.workflow(any(),anyString())).thenReturn("modified workflow".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> coordinator.enroll(enrollment(),"local-developer")).isInstanceOf(VerificationRejected.class);
        assertThat(store.repository(101).orElseThrow().policyVersion()).isEqualTo(1);
    }

    @Test void signatureAuthenticatesRawUnicodeBytesAndRejectsTampering() throws Exception {
        byte[] body="{\"zen\":\"développeur\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
            .header("X-GitHub-Delivery",UUID.randomUUID()).header("X-GitHub-Event","ping").header("X-Hub-Signature-256",sign(body)))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("IGNORED"));
        mvc.perform(post("/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"zen\":\"changed\"}")
            .header("X-GitHub-Delivery",UUID.randomUUID()).header("X-GitHub-Event","ping").header("X-Hub-Signature-256",sign(body)))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_deliveries",Integer.class)).isEqualTo(1);
    }

    @Test void missingSignatureAndOversizedBodiesAreRejectedBeforePersistence() throws Exception {
        mvc.perform(post("/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")
            .header("X-GitHub-Delivery",UUID.randomUUID()).header("X-GitHub-Event","ping"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/integrations/github/webhook").contentType(MediaType.APPLICATION_JSON).content(new byte[256*1024+1])
            .header("X-GitHub-Delivery",UUID.randomUUID()).header("X-GitHub-Event","ping"))
            .andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_deliveries",Integer.class)).isZero();
    }

    @Test void deliveryRetriesAndDifferentDeliveryIdsDeduplicateTheSameRunAttempt() throws Exception {
        enrolled(); UUID delivery=UUID.randomUUID(); byte[] body=body();
        webhooks.receive(delivery,"workflow_run",sign(body),body);
        webhooks.receive(delivery,"workflow_run",sign(body),body);
        webhooks.receive(UUID.randomUUID(),"workflow_run",sign(body),body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_deliveries",Integer.class)).isEqualTo(2);
        runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_deliveries WHERE state='VERIFIED'",Integer.class)).isEqualTo(2);
    }

    @Test void deliveryIdCannotBeReusedForDifferentSignedContent() throws Exception {
        enrolled(); UUID id=UUID.randomUUID(); byte[] one=body(); webhooks.receive(id,"workflow_run",sign(one),one);
        byte[] other=new String(one,StandardCharsets.UTF_8).replace("404","405").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> webhooks.receive(id,"workflow_run",sign(other),other)).isInstanceOf(io.kubespawn.platform.lifecycle.LifecycleException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"success","failure"})
    void lateRedeliveryReportsTheSharedJobsTerminalResult(String conclusion) throws Exception {
        enrolled(); run.put("conclusion",conclusion); queue(); runner.runOnce();
        byte[] body=body();
        String state=webhooks.receive(UUID.randomUUID(),"workflow_run",sign(body),body);
        assertThat(state).isEqualTo(conclusion.equals("success") ? "VERIFIED" : "REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_deliveries WHERE state=?",Integer.class,state)).isEqualTo(2);
    }

    @Test void unknownInstallationIsIgnoredEvenWithAValidSignature() throws Exception {
        enrolled(); byte[] body=new String(body(),StandardCharsets.UTF_8).replace("202","999").getBytes(StandardCharsets.UTF_8);
        assertThat(webhooks.receive(UUID.randomUUID(),"workflow_run",sign(body),body)).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"fork","workflow","branch","event","attempt","conclusion","workflow-content","source","image-scope","mutable-image","components","scan-evidence","artifact-corrupt","artifact-missing","artifact-duplicate","artifact-expired","archive-path"})
    void unsafeOrIncompleteEvidenceCannotCreateAVerifiedBuild(String fault) throws Exception {
        enrolled();
        switch (fault) {
            case "fork" -> ((ObjectNode)run.path("head_repository")).put("id",999);
            case "workflow" -> run.put("workflow_id",999);
            case "branch" -> run.put("head_branch","unapproved");
            case "event" -> run.put("event","pull_request_target");
            case "attempt" -> run.put("run_attempt",2);
            case "conclusion" -> run.put("conclusion","failure");
            case "workflow-content" -> when(github.workflow(any(),anyString())).thenReturn("unapproved".getBytes(StandardCharsets.UTF_8));
            case "source" -> manifest.put("sourceSha","b".repeat(40));
            case "image-scope" -> ((ObjectNode)manifest.path("images").path("api")).put("repository","ghcr.io/attacker/image");
            case "mutable-image" -> ((ObjectNode)manifest.path("images").path("api")).put("digest","latest");
            case "components" -> ((ObjectNode)manifest.path("images")).remove("web");
            case "scan-evidence" -> ((ObjectNode)manifest.path("evidence")).remove("scanPolicy");
            case "artifact-corrupt" -> corruptArchive=true;
            case "artifact-missing" -> missingArtifact=true;
            case "artifact-duplicate" -> duplicateArtifact=true;
            case "artifact-expired" -> expiredArtifact=true;
            case "archive-path" -> archiveName="../../build-result.json";
            default -> throw new IllegalArgumentException(fault);
        }
        queue(); runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT state FROM github_webhook_deliveries",String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments",Integer.class)).isZero();
    }

    @Test void successfulBuildAloneNeverCreatesAnEnvironment() throws Exception {
        enrolled(); queue(); runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments",Integer.class)).isZero();
    }

    @Test void movingTheBranchDoesNotMoveAnAlreadyPinnedRequest() {
        enrolled(); var request=create("owner"); runner.runOnce();
        branchHead="b".repeat(40); due(request.operationId()); runner.runOnce(); runner.runOnce();
        assertThat(lifecycle.get(request.environment().id(),"owner").requestedRevision()).isEqualTo(SHA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_build_requests",Integer.class)).isEqualTo(1);
    }

    @Test void dispatchResultCanBuildAPinnedOlderCommitOnlyForAKnownRequest() throws Exception {
        enrolled(); var request=create("owner"); runner.runOnce(); runner.runOnce();
        UUID requestId=jdbc.queryForObject("SELECT id FROM github_build_requests",UUID.class);
        run.put("event","workflow_dispatch").put("head_sha","b".repeat(40));
        manifest.put("requestId",requestId.toString());
        queue(); runner.runOnce(); due(request.operationId()); runner.runOnce();
        assertThat(lifecycle.get(request.environment().id(),"owner").phase()).isEqualTo("QUEUED");
        assertThat(lifecycle.get(request.environment().id(),"owner").requestedRevision()).isEqualTo(SHA);
    }

    @Test void anUnknownDispatchRequestIsRejected() throws Exception {
        enrolled(); run.put("event","workflow_dispatch"); manifest.put("requestId",UUID.randomUUID().toString());
        queue(); runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isZero();
    }

    @Test void deletionWhileResolvingPreventsSourceBindingAndBuildDispatch() throws Exception {
        enrolled(); var request=create("owner"); var lease=jobs.claim("worker").orElseThrow();
        var decision=processor.process(lease,jobs.prepare(lease).orElseThrow());
        lifecycle.delete(request.environment().id(),0,"owner",UUID.randomUUID().toString());
        jobs.waitForPrerequisite(lease,decision);
        assertThat(lifecycle.get(request.environment().id(),"owner").requestedRevision()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_build_requests",Integer.class)).isZero();
        assertThat(jobs.operation(request.operationId(),"owner").state()).isEqualTo("SUPERSEDED");
    }

    @Test void installationRemovalBlocksPendingVerificationAndSourceBinding() throws Exception {
        enrolled(); var request=create("owner"); var lease=jobs.claim("worker").orElseThrow();
        var decision=processor.process(lease,jobs.prepare(lease).orElseThrow());
        byte[] removal="{\"action\":\"removed\",\"installation\":{\"id\":202},\"repositories_removed\":[{\"id\":101}]}".getBytes(StandardCharsets.UTF_8);
        webhooks.receive(UUID.randomUUID(),"installation_repositories",sign(removal),removal);
        jobs.waitForPrerequisite(lease,decision);
        assertThat(store.repository(101).orElseThrow().enabled()).isFalse();
        assertThat(lifecycle.get(request.environment().id(),"owner").requestedRevision()).isNull();
    }

    @Test void policyChangesInvalidateAResultComputedUnderThePreviousPolicy() throws Exception {
        enrolled(); queue(); var lease=jobs.claim("old").orElseThrow();
        var decision=processor.process(lease,jobs.prepare(lease).orElseThrow());
        store.enroll(enrollment(),303,"operator");
        jobs.waitForPrerequisite(lease,decision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs",String.class)).isEqualTo("SUPERSEDED");
    }

    @Test void expiredRepositoryJobLeaseCannotPersistBuildEvidence() throws Exception {
        enrolled(); queue(); var lease=jobs.claim("old").orElseThrow();
        var decision=processor.process(lease,jobs.prepare(lease).orElseThrow());
        jdbc.update("UPDATE jobs SET lease_expires_at=clock_timestamp()-interval '1 second'");
        assertThat(jobs.waitForPrerequisite(lease,decision)).isFalse();
        assertThat(jobs.recoverExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_count FROM jobs",Integer.class)).isEqualTo(1);
    }

    @Test void sourceRefsOutsideTheApprovedBranchNeverResolveOrDispatch() {
        enrolled(); var request=lifecycle.create(new CreateEnvironmentRequest("example/app","feature/test",24),UUID.randomUUID(),"owner",UUID.randomUUID().toString());
        runner.runOnce();
        assertThat(jobs.operation(request.operationId(),"owner").reason()).isEqualTo("REF_NOT_APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_build_requests",Integer.class)).isZero();
    }

    @Test void networkErrorsRetryWithoutTrustingWebhookClaimsOrLeakingDetails() throws Exception {
        enrolled(); queue();
        when(github.get(any(),anyString())).thenThrow(new IllegalStateException("token=private-value"));
        runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM jobs",String.class)).isEqualTo("PROCESSOR_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_builds",Integer.class)).isZero();
    }

    @Test void buildProgressIsOwnerScopedAndSetupStatusRequiresBearer() throws Exception {
        enrolled(); create("other-owner");
        mvc.perform(get("/api/v1/github/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/github/environment-builds").header("Authorization",TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        when(github.configured()).thenReturn(false);
        create("local-developer");
        mvc.perform(get("/api/v1/github/environment-builds").header("Authorization",TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("GITHUB_SETUP_REQUIRED"));
    }

    private Enrollment enrollment() { return new Enrollment(101,202,"example/app","main",".github/workflows/preview-build.yaml",
        GitHubCoordinator.hash(WORKFLOW),"ghcr.io/example/app-preview-api","ghcr.io/example/app-preview-web","c".repeat(40),"d".repeat(64)); }
    private void enrolled() { store.enroll(enrollment(),303,"operator"); }
    private LifecycleService.Mutation create(String actor) { return lifecycle.create(new CreateEnvironmentRequest("example/app","main",24),UUID.randomUUID(),actor,UUID.randomUUID().toString()); }
    private void due(UUID id) { jdbc.update("UPDATE jobs SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    private void queue() throws Exception { byte[] body=body(); webhooks.receive(UUID.randomUUID(),"workflow_run",sign(body),body); }
    private byte[] body() { return "{\"action\":\"completed\",\"installation\":{\"id\":202},\"repository\":{\"id\":101,\"full_name\":\"example/app\"},\"workflow_run\":{\"id\":404,\"run_attempt\":1}}".getBytes(StandardCharsets.UTF_8); }
    private String sign(byte[] body) throws Exception { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return "sha256="+HexFormat.of().formatHex(mac.doFinal(body)); }
    private byte[] archive() throws Exception {
        var output=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(output)) { var entry=new ZipEntry(archiveName); entry.setTime(0); zip.putNextEntry(entry); zip.write(json.writeValueAsBytes(manifest)); zip.closeEntry(); }
        return output.toByteArray();
    }
}
