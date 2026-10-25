package io.kubespawn.platform.github;

import static io.kubespawn.platform.github.BuildModels.*;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubStore {
    private final JdbcTemplate jdbc;
    public GitHubStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Optional<Repository> repository(String name) {
        return jdbc.query("SELECT * FROM github_repositories WHERE full_name=?", this::mapRepository, name.toLowerCase(java.util.Locale.ROOT)).stream().findFirst();
    }
    public Optional<Repository> repository(long id) {
        return jdbc.query("SELECT * FROM github_repositories WHERE repository_id=?", this::mapRepository, id).stream().findFirst();
    }
    public List<Repository> repositories() { return jdbc.query("SELECT * FROM github_repositories ORDER BY full_name", this::mapRepository); }

    @Transactional
    public Repository enroll(Enrollment value, long workflowId, String actor) {
        jdbc.update("""
            INSERT INTO github_repositories(repository_id, installation_id, full_name, allowed_branch, workflow_id,
                workflow_path, workflow_sha256, api_image, web_image, chart_revision, template_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(repository_id) DO UPDATE SET installation_id=excluded.installation_id, full_name=excluded.full_name,
                allowed_branch=excluded.allowed_branch, workflow_id=excluded.workflow_id, workflow_path=excluded.workflow_path,
                workflow_sha256=excluded.workflow_sha256, api_image=excluded.api_image, web_image=excluded.web_image,
                chart_revision=excluded.chart_revision, template_hash=excluded.template_hash, enabled=true,
                policy_version=github_repositories.policy_version+1, updated_at=clock_timestamp()
            """, value.repositoryId(), value.installationId(), value.repository().toLowerCase(java.util.Locale.ROOT),
            value.branch(), workflowId, value.workflowPath(), value.workflowSha256(), value.apiImage(), value.webImage(),
            value.chartRevision(), value.templateHash());
        jdbc.update("""
            INSERT INTO audit_events(id, actor, action, request_id, details)
            VALUES (?, ?, 'GITHUB_REPOSITORY_ENROLLED', ?, jsonb_build_object('repositoryId', ?::bigint))
            """, UUID.randomUUID(), actor, UUID.randomUUID().toString(), value.repositoryId());
        return repository(value.repositoryId()).orElseThrow();
    }

    @Transactional
    public void disable(long installation, Long repository) {
        jdbc.update("""
            UPDATE github_repositories SET enabled=false, updated_at=clock_timestamp()
            WHERE installation_id=? AND (?::bigint IS NULL OR repository_id=?::bigint)
            """, installation, repository, repository);
    }

    @Transactional
    public String receive(UUID delivery, String event, String hash, Long installation, Long repository,
            Long run, Integer attempt, boolean eligible) {
        int inserted=jdbc.update("""
            INSERT INTO github_webhook_deliveries(delivery_id,event_type,payload_hash,installation_id,repository_id,run_id,run_attempt,state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(delivery_id) DO NOTHING
            """, delivery,event,hash,installation,repository,run,attempt,eligible ? "QUEUED" : "IGNORED");
        var original=jdbc.queryForMap("SELECT payload_hash,event_type,state FROM github_webhook_deliveries WHERE delivery_id=? FOR UPDATE", delivery);
        if (!hash.equals(original.get("payload_hash")) || !event.equals(original.get("event_type"))) {
            throw new LifecycleException(HttpStatus.CONFLICT,"DELIVERY_ID_CONFLICT","Delivery ID was already used with different content.");
        }
        if (inserted==0 || !eligible) return original.get("state").toString();
        Repository policy=repository(repository).orElseThrow();
        UUID job=queue("VERIFY_GITHUB_BUILD", "github:verify:" + repository + ":" + run + ":" + attempt + ":" + policy.policyVersion(), policy.policyVersion());
        // Serialize linking a late receipt with finalization of the shared job. Otherwise a receipt
        // arriving just after completion could remain QUEUED forever without another worker pass.
        String jobState=jdbc.queryForObject("SELECT state FROM jobs WHERE id=? FOR UPDATE",String.class,job);
        String state=jobState.equals("COMPLETE") ? "VERIFIED"
            : jobState.equals("FAILED") || jobState.equals("SUPERSEDED") ? "REJECTED" : "QUEUED";
        jdbc.update("UPDATE github_webhook_deliveries SET job_id=?,state=? WHERE delivery_id=?",job,state,delivery);
        return state;
    }

    public Work work(UUID job, String type) {
        if (type.equals("DISPATCH_GITHUB_BUILD")) return jdbc.queryForObject("""
            SELECT id,repository_id,source_sha FROM github_build_requests WHERE job_id=?
            """, (rs,row) -> new Work(null,rs.getLong(2),0,0,rs.getObject(1,UUID.class),rs.getString(3)),job);
        return jdbc.query("""
            SELECT delivery_id,repository_id,run_id,run_attempt FROM github_webhook_deliveries
            WHERE job_id=? ORDER BY received_at LIMIT 1
            """, (rs,row) -> new Work(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3),rs.getInt(4),null,null),job)
            .stream().findFirst().orElseThrow(() -> new VerificationRejected("MISSING_DELIVERY"));
    }

    public Optional<UUID> verified(long repository, String sha, long policy) {
        return jdbc.query("""
            SELECT id FROM verified_builds WHERE repository_id=? AND source_sha=? AND policy_version=?
            ORDER BY verified_at DESC,id DESC LIMIT 1
            """, (rs,row) -> rs.getObject(1,UUID.class),repository,sha,policy).stream().findFirst();
    }

    public boolean requested(UUID id, long repository, String sha, long policy) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM github_build_requests WHERE id=? AND repository_id=? AND source_sha=? AND policy_version=?)
            """, Boolean.class,id,repository,sha,policy));
    }

    // Called inside the worker's fenced transaction after locking the current environment intent.
    public boolean bind(UUID environment, Source source) {
        var policy=lockedPolicy(source.repositoryId(), source.policyVersion());
        if (policy.isEmpty()) return false;
        var current=jdbc.queryForMap("SELECT source_id,repository_name,requested_revision,verified_build_id,desired_state='ACTIVE' AND expires_at>clock_timestamp() AS eligible FROM environments WHERE id=?",environment);
        if (!Boolean.TRUE.equals(current.get("eligible"))) return false;
        if (!current.get("repository_name").equals(policy.get().name())) return false;
        Object pinned=current.get("requested_revision");
        if (pinned!=null && !pinned.equals(source.sha())) return false;
        jdbc.update("UPDATE environments SET requested_revision=?,updated_at=clock_timestamp() WHERE id=? AND requested_revision IS NULL",source.sha(),environment);
        jdbc.update("UPDATE environment_sources SET repository_id=?,installation_id=?,updated_at=clock_timestamp() WHERE id=?",
            source.repositoryId(),source.installationId(),current.get("source_id"));
        var build=verified(source.repositoryId(),source.sha(),source.policyVersion());
        if (source.buildId()!=null && build.filter(source.buildId()::equals).isPresent()) {
            boolean changed=!source.buildId().equals(current.get("verified_build_id"));
            jdbc.update("""
                UPDATE environments SET verified_build_id=?,chart_revision=?,phase='QUEUED',updated_at=clock_timestamp()
                WHERE id=? AND desired_state='ACTIVE' AND expires_at>clock_timestamp()
                """,source.buildId(),policy.get().chartRevision(),environment);
            if (changed) jdbc.update("""
                INSERT INTO audit_events(id,environment_id,actor,action,request_id,details)
                VALUES (?,?,'github-verifier','BUILD_VERIFIED',?,jsonb_build_object('buildId',?::text,'sourceSha',?::text))
                """,UUID.randomUUID(),environment,UUID.randomUUID().toString(),source.buildId().toString(),source.sha());
        } else {
            jdbc.update("""
                UPDATE environments SET verified_build_id=NULL,phase='WAITING_BUILD',updated_at=clock_timestamp()
                WHERE id=? AND phase IN ('WAITING_BUILD','QUEUED')
                """,environment);
            requestBuild(policy.get(),source.sha());
        }
        return true;
    }

    private void requestBuild(Repository repository, String sha) {
        // Serialize request identity under the already-held policy row lock.
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM github_build_requests WHERE repository_id=? AND source_sha=? AND policy_version=?)
            """,Boolean.class,repository.id(),sha,repository.policyVersion()))) return;
        UUID id=UUID.randomUUID();
        UUID job=queue("DISPATCH_GITHUB_BUILD","github:dispatch:"+repository.id()+":"+sha+":"+repository.policyVersion(),repository.policyVersion());
        jdbc.update("INSERT INTO github_build_requests(id,repository_id,source_sha,policy_version,job_id) VALUES (?,?,?,?,?)",
            id,repository.id(),sha,repository.policyVersion(),job);
    }

    public boolean dispatched(UUID request, long policy) {
        var row=jdbc.queryForMap("SELECT repository_id FROM github_build_requests WHERE id=?",request);
        if (lockedPolicy(((Number)row.get("repository_id")).longValue(),policy).isEmpty()) return false;
        jdbc.update("UPDATE github_build_requests SET state='DISPATCHED',updated_at=clock_timestamp() WHERE id=? AND state='PENDING'",request);
        return true;
    }

    public boolean verified(Verified result, UUID job) {
        if (lockedPolicy(result.repositoryId(),result.policyVersion()).isEmpty()) return false;
        jdbc.update("""
            INSERT INTO verified_builds(id,repository_id,source_sha,policy_version,run_id,run_attempt,workflow_revision,
                workflow_sha256,artifact_id,artifact_digest,api_image,api_digest,web_image,web_digest,chart_revision,template_hash,evidence)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
            ON CONFLICT(repository_id,run_id,run_attempt,policy_version) DO NOTHING
            """,result.id(),result.repositoryId(),result.sourceSha(),result.policyVersion(),result.runId(),result.runAttempt(),
            result.workflowRevision(),result.workflowHash(),result.artifactId(),result.artifactDigest(),result.apiImage(),
            result.apiDigest(),result.webImage(),result.webDigest(),result.chartRevision(),result.templateHash(),result.evidence());
        jdbc.update("UPDATE github_webhook_deliveries SET state='VERIFIED' WHERE job_id=?",job);
        if (result.requestId()!=null) jdbc.update("UPDATE github_build_requests SET state='VERIFIED',updated_at=clock_timestamp() WHERE id=?",result.requestId());
        jdbc.update("""
            INSERT INTO outbox_events(id,aggregate_id,aggregate_version,event_type,payload)
            VALUES (?,?,?,'BUILD_VERIFIED',jsonb_build_object('repositoryId',?::bigint,'sourceSha',?::text))
            """,UUID.randomUUID(),result.id(),result.policyVersion(),result.repositoryId(),result.sourceSha());
        return true;
    }

    public void rejected(UUID job) { jdbc.update("UPDATE github_webhook_deliveries SET state='REJECTED' WHERE job_id=?",job); }

    public List<Progress> progress(String actor, boolean configured) {
        return jdbc.query("""
            SELECT e.id,e.requested_revision,r.repository_id,r.enabled,r.full_name,b.run_id,b.api_image,b.api_digest,b.web_image,b.web_digest,
                br.state AS request_state,j.state AS dispatch_state FROM environments e
            JOIN environment_sources s ON s.id=e.source_id
            LEFT JOIN github_repositories r ON (r.repository_id=s.repository_id OR (s.repository_id IS NULL AND r.full_name=e.repository_name))
            LEFT JOIN verified_builds b ON b.id=e.verified_build_id AND b.policy_version=r.policy_version AND r.enabled
            LEFT JOIN github_build_requests br ON br.repository_id=r.repository_id AND br.source_sha=e.requested_revision AND br.policy_version=r.policy_version
            LEFT JOIN jobs j ON j.id=br.job_id WHERE e.owner_actor=? ORDER BY e.created_at DESC,e.id DESC LIMIT 100
            """,(rs,row) -> {
                Long run=rs.getObject("run_id",Long.class);
                String state=!configured ? "GITHUB_SETUP_REQUIRED" : rs.getObject("repository_id")==null ? "REPOSITORY_NOT_ENROLLED"
                    : !rs.getBoolean("enabled") ? "REPOSITORY_DISABLED" : run!=null ? "VERIFIED"
                    : "FAILED".equals(rs.getString("dispatch_state")) ? "BUILD_DISPATCH_FAILED"
                    : "DISPATCHED".equals(rs.getString("request_state")) ? "BUILDING"
                    : rs.getString("requested_revision")==null ? "RESOLVING_SOURCE" : "BUILD_REQUESTED";
                return new Progress(rs.getObject("id",UUID.class),state,rs.getString("requested_revision"),run,
                    run==null ? null : "https://github.com/"+rs.getString("full_name")+"/actions/runs/"+run,
                    rs.getString("api_image"),rs.getString("api_digest"),rs.getString("web_image"),rs.getString("web_digest"));
            },actor);
    }

    private Optional<Repository> lockedPolicy(long id,long version) {
        return jdbc.query("SELECT * FROM github_repositories WHERE repository_id=? AND policy_version=? AND enabled FOR UPDATE",
            this::mapRepository,id,version).stream().findFirst();
    }
    private UUID queue(String type,String key,long version) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,job_type,intent_version,deduplication_key) VALUES (?,?,?,?) ON CONFLICT(deduplication_key) DO NOTHING",id,type,version,key);
        return jdbc.queryForObject("SELECT id FROM jobs WHERE deduplication_key=?",UUID.class,key);
    }
    private Repository mapRepository(ResultSet rs,int row) throws SQLException {
        return new Repository(rs.getLong("repository_id"),rs.getLong("installation_id"),rs.getString("full_name"),
            rs.getString("allowed_branch"),rs.getLong("workflow_id"),rs.getString("workflow_path"),rs.getString("workflow_sha256"),
            rs.getString("api_image"),rs.getString("web_image"),rs.getString("chart_revision"),rs.getString("template_hash"),
            rs.getLong("policy_version"),rs.getBoolean("enabled"));
    }
}
