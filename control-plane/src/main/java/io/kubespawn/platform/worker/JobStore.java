package io.kubespawn.platform.worker;

import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.github.GitHubStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobStore {
    private final JdbcTemplate jdbc;
    private final WorkerSettings settings;
    private final GitHubStore github;
    private final io.kubespawn.platform.provisioning.ProvisioningStore provisioning;

    public JobStore(JdbcTemplate jdbc, WorkerSettings settings, GitHubStore github,io.kubespawn.platform.provisioning.ProvisioningStore provisioning) {
        this.jdbc = jdbc; this.settings = settings; this.github=github; this.provisioning=provisioning;
    }

    public record Operation(UUID id, UUID environmentId, String type, String state, long intentVersion,
            int attempts, int failureCount, String reason, String message, Instant createdAt, Instant updatedAt,
            Instant availableAt, Instant leaseExpiresAt, Instant lastHeartbeatAt, Instant finishedAt) {}

    @Transactional
    public Optional<JobLease> claim(String worker) {
        List<JobLease> candidates = jdbc.query("""
            SELECT j.* FROM jobs j WHERE j.state IN ('PENDING', 'WAITING')
                AND j.available_at <= clock_timestamp()
                AND NOT EXISTS (SELECT 1 FROM environment_work_leases l
                    WHERE l.environment_id=j.environment_id AND l.expires_at > clock_timestamp())
            ORDER BY CASE WHEN j.job_type='TEARDOWN_ENVIRONMENT' THEN 0 ELSE 1 END, j.available_at, j.id
            LIMIT 20 FOR UPDATE OF j SKIP LOCKED
            """, (rs, row) -> new JobLease(rs.getObject("id", UUID.class), rs.getObject("environment_id", UUID.class),
                rs.getString("job_type"), rs.getLong("intent_version"), worker, rs.getLong("lease_token") + 1, 0));
        for (JobLease candidate : candidates) {
            List<Long> tokens = candidate.environmentId()==null ? List.of(0L) : jdbc.query("""
                INSERT INTO environment_work_leases(environment_id, job_id, worker_id, fencing_token, expires_at)
                VALUES (?, ?, ?, 1, clock_timestamp() + ?::bigint * interval '1 millisecond')
                ON CONFLICT (environment_id) DO UPDATE SET job_id=excluded.job_id, worker_id=excluded.worker_id,
                    fencing_token=environment_work_leases.fencing_token+1,
                    expires_at=clock_timestamp() + ?::bigint * interval '1 millisecond'
                WHERE environment_work_leases.expires_at <= clock_timestamp()
                RETURNING fencing_token
                """, (rs, row) -> rs.getLong(1), candidate.environmentId(), candidate.id(), worker,
                settings.lease().toMillis(), settings.lease().toMillis());
            if (tokens.isEmpty()) continue;
            JobLease lease = new JobLease(candidate.id(), candidate.environmentId(), candidate.type(),
                candidate.intentVersion(), worker, candidate.jobToken(), tokens.getFirst());
            jdbc.update("""
                UPDATE jobs SET state='RUNNING', lease_owner=?, lease_token=?, environment_lease_token=?,
                    lease_expires_at=clock_timestamp() + ?::bigint * interval '1 millisecond',
                    attempts=attempts+1, started_at=COALESCE(started_at, clock_timestamp()),
                    last_heartbeat_at=clock_timestamp(), updated_at=clock_timestamp(),
                    reason='PROCESSING', message='The worker is checking the current intent.' WHERE id=?
                """, worker, lease.jobToken(), lease.environmentToken(), settings.lease().toMillis(), lease.id());
            return Optional.of(lease);
        }
        return Optional.empty();
    }

    @Transactional
    public Optional<JobProcessor.Intent> prepare(JobLease lease) {
        if (!fenced(lease)) return Optional.empty();
        JobProcessor.Intent intent = intent(lease);
        if (!fenced(lease)) return Optional.empty();
        if (stale(lease, intent)) { supersede(lease); return Optional.empty(); }
        return Optional.of(intent);
    }

    @Transactional
    public boolean renew(JobLease lease) {
        if (!fenced(lease)) return false;
        if (lease.environmentId()!=null) jdbc.update("""
            UPDATE environment_work_leases SET expires_at=clock_timestamp() + ?::bigint * interval '1 millisecond'
            WHERE environment_id=?
            """, settings.lease().toMillis(), lease.environmentId());
        jdbc.update("""
            UPDATE jobs SET lease_expires_at=clock_timestamp() + ?::bigint * interval '1 millisecond',
                last_heartbeat_at=clock_timestamp(), updated_at=clock_timestamp() WHERE id=?
            """, settings.lease().toMillis(), lease.id());
        return true;
    }

    @Transactional
    public boolean waitForPrerequisite(JobLease lease, JobProcessor.Decision decision) {
        if (!fenced(lease)) return false;
        JobProcessor.Intent intent = intent(lease);
        if (!fenced(lease)) return false;
        if (stale(lease, intent)) { supersede(lease); return true; }
        boolean complete=decision.verified()!=null || decision.dispatchedRequestId()!=null;
        if (decision.source()!=null && !github.bind(lease.environmentId(),decision.source())) {
            decision=new JobProcessor.Decision("REPOSITORY_POLICY_CHANGED","Repository policy changed. Waiting for a fresh verification check.");
        }
        if (decision.verified()!=null && !github.verified(decision.verified(),lease.id())) { supersede(lease); return true; }
        if (decision.dispatchedRequestId()!=null && !github.dispatched(decision.dispatchedRequestId(),lease.intentVersion())) { supersede(lease); return true; }
        if (decision.provisioning()!=null) provisioning.observed(lease,decision.provisioning());
        jdbc.update("""
            UPDATE jobs SET state=?, reason=?, message=?, last_error=NULL,
                available_at=clock_timestamp() + ?::bigint * interval '1 millisecond',
                finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                updated_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL WHERE id=?
            """, complete ? "COMPLETE" : "WAITING", decision.reason(), decision.message(), settings.prerequisiteDelay().toMillis(),complete, lease.id());
        release(lease);
        return true;
    }

    @Transactional
    public boolean fail(JobLease lease, String code, boolean retryable) {
        if (!fenced(lease)) return false;
        JobProcessor.Intent intent = intent(lease);
        if (!fenced(lease)) return false;
        if (stale(lease, intent)) { supersede(lease); return true; }
        failure(lease, code, retryable);
        return true;
    }

    @Transactional
    public int recoverExpired() {
        List<JobLease> expired = jdbc.query("""
            SELECT * FROM jobs WHERE state='RUNNING' AND lease_expires_at <= clock_timestamp()
            ORDER BY lease_expires_at, id LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, row) -> new JobLease(rs.getObject("id", UUID.class), rs.getObject("environment_id", UUID.class),
                rs.getString("job_type"), rs.getLong("intent_version"), rs.getString("lease_owner"),
                rs.getLong("lease_token"), rs.getLong("environment_lease_token")));
        for (JobLease lease : expired) {
            // Use the same job -> environment lease -> intent lock order as renewal/finalization.
            jdbc.query("SELECT environment_id FROM environment_work_leases WHERE environment_id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), lease.environmentId());
            if (stale(lease, intent(lease))) supersede(lease);
            else failure(lease, "LEASE_EXPIRED", true);
        }
        return expired.size();
    }

    @Transactional(readOnly = true)
    public Operation operation(UUID id, String actor) {
        return jdbc.query("""
            SELECT j.* FROM jobs j JOIN environments e ON e.id=j.environment_id WHERE j.id=? AND e.owner_actor=?
            """, this::operation, id, actor).stream().findFirst().orElseThrow(() ->
                new LifecycleException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Operation not found."));
    }

    @Transactional(readOnly = true)
    public List<Operation> latestOperations(String actor) {
        return jdbc.query("""
            SELECT latest.* FROM (
                SELECT DISTINCT ON (j.environment_id) j.* FROM jobs j
                JOIN environments e ON e.id=j.environment_id WHERE e.owner_actor=?
                ORDER BY j.environment_id, j.intent_version DESC, j.created_at DESC, j.id DESC
            ) latest ORDER BY latest.created_at DESC, latest.id DESC LIMIT 100
            """, this::operation, actor);
    }

    @Transactional
    public Operation retry(UUID id,String actor,long version,String requestId) {
        var operation=jdbc.query("SELECT j.* FROM jobs j JOIN environments e ON e.id=j.environment_id WHERE j.id=? AND e.owner_actor=? FOR UPDATE OF j",
            this::operation,id,actor).stream().findFirst().orElseThrow(()->new LifecycleException(HttpStatus.NOT_FOUND,"NOT_FOUND","Operation not found."));
        var intent=jdbc.queryForMap("SELECT intent_version,desired_state,expires_at>clock_timestamp() AS unexpired FROM environments WHERE id=? FOR UPDATE",operation.environmentId());
        if (((Number)intent.get("intent_version")).longValue()!=version || operation.intentVersion()!=version)
            throw new LifecycleException(HttpStatus.PRECONDITION_FAILED,"STALE_VERSION","Refresh this environment before retrying.");
        if (!operation.state().equals("FAILED") || !intent.get("desired_state").equals("ACTIVE") || !Boolean.TRUE.equals(intent.get("unexpired")))
            throw new LifecycleException(HttpStatus.CONFLICT,"NOT_RETRYABLE","Only a failed operation for the current active, unexpired intent can be retried.");
        jdbc.update("UPDATE jobs SET state='PENDING',failure_count=0,reason='RETRY_REQUESTED',message='An explicit retry was requested.',last_error=NULL,finished_at=NULL,available_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);
        jdbc.update("INSERT INTO audit_events(id,environment_id,actor,action,request_id,details) VALUES (?,?,?,'OPERATION_RETRY_REQUESTED',?,jsonb_build_object('operationId',?::text,'previousFailures',?::int))",
            UUID.randomUUID(),operation.environmentId(),actor,requestId,id.toString(),operation.failureCount());
        return operation(id,actor);
    }

    // These row locks are held only for short DB transactions, never while a processor runs.
    private boolean fenced(JobLease lease) {
        boolean job = !jdbc.query("""
            SELECT id FROM jobs WHERE id=? AND state='RUNNING' AND lease_owner=? AND lease_token=?
                AND environment_lease_token=? AND lease_expires_at > clock_timestamp() FOR UPDATE
            """, (rs, row) -> rs.getObject(1, UUID.class), lease.id(), lease.workerId(), lease.jobToken(), lease.environmentToken()).isEmpty();
        if (lease.environmentId()==null) return job && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT lease_expires_at>clock_timestamp() FROM jobs WHERE id=?",Boolean.class,lease.id()));
        boolean environment = job && !jdbc.query("""
            SELECT environment_id FROM environment_work_leases WHERE environment_id=? AND job_id=? AND worker_id=?
                AND fencing_token=? AND expires_at > clock_timestamp() FOR UPDATE
            """, (rs, row) -> rs.getObject(1, UUID.class), lease.environmentId(), lease.id(), lease.workerId(), lease.environmentToken()).isEmpty();
        // Predicates may have been evaluated before waiting for a row lock. Check time again after both locks.
        return environment && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT j.lease_expires_at > clock_timestamp() AND l.expires_at > clock_timestamp()
            FROM jobs j JOIN environment_work_leases l ON l.environment_id=j.environment_id WHERE j.id=?
            """, Boolean.class, lease.id()));
    }

    private JobProcessor.Intent intent(JobLease lease) {
        if (lease.environmentId()==null) return new JobProcessor.Intent(lease.intentVersion(),"BUILD","VERIFYING",null,null,null);
        return jdbc.queryForObject("""
            SELECT intent_version, desired_state, phase, requested_revision, repository_name, requested_ref FROM environments WHERE id=? FOR UPDATE
            """, (rs, row) -> new JobProcessor.Intent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),rs.getString(5),rs.getString(6)), lease.environmentId());
    }

    private boolean stale(JobLease lease, JobProcessor.Intent intent) {
        return intent.version() != lease.intentVersion()
            || (lease.type().equals("RECONCILE_ENVIRONMENT") && !intent.desiredState().equals("ACTIVE"))
            || (lease.type().equals("TEARDOWN_ENVIRONMENT") && !intent.desiredState().equals("ABSENT"));
    }

    private void supersede(JobLease lease) {
        jdbc.update("""
            UPDATE jobs SET state='SUPERSEDED', reason='NEWER_INTENT', message='A newer intent replaced this operation.',
                updated_at=clock_timestamp(), finished_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL WHERE id=?
            """, lease.id());
        if (lease.environmentId()==null) github.rejected(lease.id());
        release(lease);
    }

    private void failure(JobLease lease, String code, boolean retryable) {
        int failures = jdbc.queryForObject("SELECT failure_count+1 FROM jobs WHERE id=?", Integer.class, lease.id());
        boolean terminal = !lease.type().equals("TEARDOWN_ENVIRONMENT") && (!retryable || failures >= settings.maxFailures());
        // Only controlled codes/messages are persisted; exception details may contain credentials.
        String message = switch (code) {
            case "LEASE_EXPIRED" -> "The worker lease expired before a result was saved.";
            case "PROCESSING_TIMEOUT" -> "Processing exceeded its time limit.";
            case "UNSUPPORTED_JOB_TYPE" -> "This worker does not support the job type.";
            default -> "The worker could not process this operation.";
        };
        jdbc.update("""
            UPDATE jobs SET state=?, failure_count=?, reason=?, message=?, last_error=?,
                available_at=clock_timestamp() + ?::bigint * interval '1 millisecond', updated_at=clock_timestamp(),
                finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                lease_owner=NULL, lease_expires_at=NULL WHERE id=?
            """, terminal ? "FAILED" : "PENDING", failures, code, message, code, retryDelay(failures), terminal, lease.id());
        if (terminal && lease.environmentId()==null) github.rejected(lease.id());
        release(lease);
    }

    private long retryDelay(int failures) {
        long cap = settings.retryMax().toMillis();
        long base = settings.retryBase().toMillis();
        long exponential = base;
        for (int i=1; i<failures && exponential<cap; i++) exponential = exponential > cap/2 ? cap : exponential*2;
        long upper = Math.min(exponential, cap);
        // Equal jitter preserves a positive delay and avoids synchronized retry storms.
        return ThreadLocalRandom.current().nextLong(Math.max(1, upper/2), upper+1);
    }

    private void release(JobLease lease) {
        jdbc.update("""
            UPDATE environment_work_leases SET job_id=NULL, worker_id=NULL, expires_at=clock_timestamp()
            WHERE environment_id=? AND job_id=? AND worker_id=? AND fencing_token=?
            """, lease.environmentId(), lease.id(), lease.workerId(), lease.environmentToken());
    }

    private Operation operation(ResultSet rs, int row) throws SQLException {
        return new Operation(rs.getObject("id", UUID.class), rs.getObject("environment_id", UUID.class),
            rs.getString("job_type"), rs.getString("state"), rs.getLong("intent_version"), rs.getInt("attempts"),
            rs.getInt("failure_count"), rs.getString("reason"), rs.getString("message"), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "available_at"), instant(rs, "lease_expires_at"),
            instant(rs, "last_heartbeat_at"), instant(rs, "finished_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
