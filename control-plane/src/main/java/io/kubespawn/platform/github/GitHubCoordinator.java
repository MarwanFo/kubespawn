package io.kubespawn.platform.github;

import static io.kubespawn.platform.github.BuildModels.*;

import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.worker.JobLease;
import io.kubespawn.platform.worker.JobProcessor;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GitHubCoordinator {
    private final GitHubGateway github;
    private final GitHubStore store;
    private final ObjectMapper json;
    public GitHubCoordinator(GitHubGateway github,GitHubStore store,ObjectMapper json) { this.github=github; this.store=store; this.json=json; }
    public boolean configured() { return github.configured(); }

    // Network work deliberately occurs outside lifecycle/worker persistence transactions.
    public Repository enroll(Enrollment value,String actor) {
        if (!configured()) throw new LifecycleException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_SETUP_REQUIRED","Configure the GitHub App before enrolling a repository.");
        if (value.apiImage().equals(value.webImage())) throw new LifecycleException(HttpStatus.BAD_REQUEST,"INVALID_IMAGES","API and web images must have distinct repository names.");
        Repository proposed=new Repository(value.repositoryId(),value.installationId(),value.repository().toLowerCase(Locale.ROOT),
            value.branch(),1,value.workflowPath(),value.workflowSha256(),value.apiImage(),value.webImage(),value.chartRevision(),value.templateHash(),1,true);
        JsonNode repo=github.get(proposed,"/repos/"+proposed.name());
        require(repo.path("id").asLong()==proposed.id() && repo.path("full_name").asText().equalsIgnoreCase(proposed.name()),"REPOSITORY_ID_MISMATCH");
        require(!repo.path("fork").asBoolean(),"FORK_REJECTED");
        String head=resolve(proposed,proposed.branch());
        require(hash(github.workflow(proposed,head)).equals(proposed.workflowHash()),"WORKFLOW_NOT_APPROVED");
        JsonNode workflow=github.get(proposed,"/repos/"+proposed.name()+"/actions/workflows/"+proposed.workflowPath());
        require(workflow.path("path").asText().equals(proposed.workflowPath()) && workflow.path("state").asText().equals("active"),"WORKFLOW_NOT_ACTIVE");
        require(workflow.path("id").asLong()>0,"WORKFLOW_ID_MISSING");
        JsonNode chart=github.get(proposed,"/repos/"+proposed.name()+"/commits/"+value.chartRevision());
        require(chart.path("sha").asText().equals(value.chartRevision()),"CHART_REVISION_MISMATCH");
        return store.enroll(value,workflow.path("id").asLong(),actor);
    }

    public JobProcessor.Decision reconcile(JobLease job,JobProcessor.Intent intent) {
        if (!configured()) return new JobProcessor.Decision("GITHUB_SETUP_REQUIRED","Connect a GitHub App to resolve this request and verify its build.");
        var candidate=store.repository(intent.repository());
        if (candidate.isEmpty()) return new JobProcessor.Decision("REPOSITORY_NOT_ENROLLED","Enroll this repository and approve its build workflow before continuing.");
        Repository repo=candidate.get();
        if (!repo.enabled()) return new JobProcessor.Decision("REPOSITORY_DISABLED","GitHub access for this repository is disabled. Review its installation and enrollment.");
        if (!intent.requestedRef().equals(repo.branch())) return new JobProcessor.Decision("REF_NOT_APPROVED","This request's ref is outside the repository's approved branch policy.");
        String sha=intent.requestedRevision()==null ? resolve(repo,repo.branch()) : intent.requestedRevision();
        var build=store.verified(repo.id(),sha,repo.policyVersion());
        Source source=new Source(repo.id(),repo.installationId(),repo.policyVersion(),sha,build.orElse(null));
        return new JobProcessor.Decision(build.isPresent() ? "KUBERNETES_INTEGRATION_REQUIRED" : "BUILD_VERIFICATION_REQUIRED",
            build.isPresent() ? "Build verified. Waiting for Kubernetes provisioning integration."
                : "Build requested. Waiting for a successful run and verified image digests.",source,null,null);
    }

    public JobProcessor.Decision process(JobLease job) {
        if (!configured()) return new JobProcessor.Decision("GITHUB_SETUP_REQUIRED","GitHub processing is paused until the App is configured.");
        Work work=store.work(job.id(),job.type());
        Repository repo=store.repository(work.repositoryId()).orElseThrow(() -> new VerificationRejected("REPOSITORY_NOT_ENROLLED"));
        require(repo.enabled() && repo.policyVersion()==job.intentVersion(),"REPOSITORY_POLICY_CHANGED");
        if (job.type().equals("DISPATCH_GITHUB_BUILD")) {
            String head=resolve(repo,repo.branch());
            require(hash(github.workflow(repo,head)).equals(repo.workflowHash()),"WORKFLOW_NOT_APPROVED");
            require(store.repository(repo.id()).filter(current -> current.enabled() && current.policyVersion()==repo.policyVersion()).isPresent(),"REPOSITORY_POLICY_CHANGED");
            github.dispatch(repo,work.sourceSha(),work.requestId().toString());
            return new JobProcessor.Decision("BUILD_DISPATCHED","The approved build workflow was dispatched.",null,null,work.requestId());
        }
        return verify(job,work,repo);
    }

    private JobProcessor.Decision verify(JobLease job,Work work,Repository repo) {
        JsonNode run=github.get(repo,"/repos/"+repo.name()+"/actions/runs/"+work.runId());
        require(run.path("id").asLong()==work.runId(),"RUN_ID_MISMATCH");
        require(run.path("run_attempt").asInt()==work.attempt(),"STALE_RUN_ATTEMPT");
        require(run.path("repository").path("id").asLong()==repo.id()
            && run.path("head_repository").path("id").asLong()==repo.id(),"FORK_OR_REPOSITORY_MISMATCH");
        require(run.path("workflow_id").asLong()==repo.workflowId() && run.path("path").asText().split("@",2)[0].equals(repo.workflowPath()),"WORKFLOW_ID_MISMATCH");
        require(run.path("head_branch").asText().equals(repo.branch()),"BRANCH_NOT_APPROVED");
        String event=run.path("event").asText();
        require(event.equals("push") || event.equals("workflow_dispatch"),"EVENT_NOT_APPROVED");
        if (!run.path("status").asText().equals("completed")) return new JobProcessor.Decision("BUILD_IN_PROGRESS","Waiting for GitHub to report a completed workflow run.");
        require(run.path("conclusion").asText().equals("success"),"BUILD_NOT_SUCCESSFUL");
        String workflowRevision=run.path("head_sha").asText(); require(workflowRevision.matches("[a-f0-9]{40}"),"INVALID_WORKFLOW_REVISION");
        require(hash(github.workflow(repo,workflowRevision)).equals(repo.workflowHash()),"WORKFLOW_NOT_APPROVED");

        JsonNode listing=github.get(repo,"/repos/"+repo.name()+"/actions/runs/"+work.runId()+"/artifacts?per_page=100");
        var artifacts=new ArrayList<JsonNode>();
        for (JsonNode artifact : listing.path("artifacts")) {
            if (artifact.path("name").asText().equals("kubespawn-build-result-"+work.attempt())) artifacts.add(artifact);
        }
        require(artifacts.size()==1,"BUILD_MANIFEST_MISSING_OR_AMBIGUOUS");
        JsonNode artifact=artifacts.getFirst();
        require(!artifact.path("expired").asBoolean() && artifact.path("size_in_bytes").asLong()>0
            && artifact.path("size_in_bytes").asLong()<=512*1024,"ARTIFACT_UNAVAILABLE");
        JsonNode artifactRun=artifact.path("workflow_run");
        require(artifactRun.path("id").asLong()==work.runId() && artifactRun.path("repository_id").asLong()==repo.id()
            && artifactRun.path("head_repository_id").asLong()==repo.id() && artifactRun.path("head_sha").asText().equals(workflowRevision),"ARTIFACT_RUN_MISMATCH");
        String digest=artifact.path("digest").asText(); require(digest.matches("sha256:[a-f0-9]{64}"),"ARTIFACT_DIGEST_MISSING");
        long artifactId=artifact.path("id").asLong(); require(artifactId>0,"ARTIFACT_ID_MISSING");
        byte[] archive=github.artifact(repo,artifactId);
        require(archive.length<=512*1024 && digest.equals("sha256:"+hash(archive)),"ARTIFACT_DIGEST_MISMATCH");
        JsonNode manifest=manifest(archive);
        require(manifest.path("schemaVersion").asInt()==1 && manifest.path("repositoryId").asLong()==repo.id()
            && manifest.path("repository").asText().equals(repo.name()) && manifest.path("runId").asLong()==work.runId()
            && manifest.path("runAttempt").asInt()==work.attempt(),"MANIFEST_IDENTITY_MISMATCH");
        String sha=manifest.path("sourceSha").asText(); require(sha.matches("[a-f0-9]{40}"),"INVALID_SOURCE_SHA");
        String request=manifest.path("requestId").asText("");
        UUID requestId=null;
        if (event.equals("workflow_dispatch")) {
            try { requestId=UUID.fromString(request); } catch (IllegalArgumentException invalid) { throw new VerificationRejected("BUILD_REQUEST_MISSING"); }
            require(store.requested(requestId,repo.id(),sha,repo.policyVersion()),"BUILD_REQUEST_MISMATCH");
        } else require(sha.equals(workflowRevision) && request.isEmpty(),"PUSH_SOURCE_MISMATCH");
        JsonNode images=manifest.path("images");
        require(images.isObject() && images.size()==2 && images.has("api") && images.has("web"),"COMPONENT_SET_MISMATCH");
        String apiDigest=image(images.path("api"),repo.apiImage());
        String webDigest=image(images.path("web"),repo.webImage());
        JsonNode evidence=manifest.path("evidence");
        require(evidence.path("scanPolicy").asText().equals("trivy-high-critical-no-ignore-v1")
            && evidence.path("sbom").asText().equals("buildkit-spdx") && evidence.path("provenance").asText().equals("buildkit-max"),"BUILD_POLICY_EVIDENCE_MISSING");
        Verified result=new Verified(UUID.randomUUID(),work.deliveryId(),repo.id(),repo.policyVersion(),sha,work.runId(),work.attempt(),
            workflowRevision,repo.workflowHash(),artifactId,digest,repo.apiImage(),apiDigest,repo.webImage(),webDigest,
            repo.chartRevision(),repo.templateHash(),json.writeValueAsString(evidence),requestId);
        return new JobProcessor.Decision("BUILD_VERIFIED","The approved workflow and complete image set were verified.",null,result,null);
    }

    private String resolve(Repository repo,String ref) {
        JsonNode value=github.get(repo,"/repos/"+repo.name()+"/commits/"+URLEncoder.encode(ref,StandardCharsets.UTF_8));
        String sha=value.path("sha").asText(); require(sha.matches("[a-f0-9]{40}"),"SOURCE_RESOLUTION_FAILED"); return sha;
    }
    private String image(JsonNode image,String approved) {
        require(image.path("repository").asText().equals(approved),"IMAGE_SCOPE_MISMATCH");
        String digest=image.path("digest").asText(); require(digest.matches("sha256:[a-f0-9]{64}"),"IMMUTABLE_IMAGE_REQUIRED"); return digest;
    }
    private JsonNode manifest(byte[] bytes) {
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry=zip.getNextEntry();
            require(entry!=null && !entry.isDirectory() && entry.getName().equals("build-result.json"),"MANIFEST_ARCHIVE_REJECTED");
            byte[] body=zip.readNBytes(64*1024+1); require(body.length<=64*1024 && zip.getNextEntry()==null,"MANIFEST_ARCHIVE_REJECTED");
            JsonNode result=json.readTree(body); require(result!=null && result.isObject(),"MANIFEST_FORMAT_REJECTED"); return result;
        } catch (VerificationRejected rejected) { throw rejected; }
        catch (Exception invalid) { throw new VerificationRejected("MANIFEST_FORMAT_REJECTED"); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean valid,String code) { if (!valid) throw new VerificationRejected(code); }
}
