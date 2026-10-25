package io.kubespawn.platform.provisioning;

import static io.kubespawn.platform.provisioning.ProvisioningModels.*;
import io.kubespawn.platform.worker.JobLease;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProvisioningStore {
    private final JdbcTemplate jdbc;
    private final ProvisioningSettings settings;
    public ProvisioningStore(JdbcTemplate jdbc, ProvisioningSettings settings) { this.jdbc=jdbc; this.settings=settings; }

    public Optional<Plan> plan(UUID environment) {
        return jdbc.query("""
            SELECT e.namespace,e.repository_name,e.requested_revision,b.* FROM environments e
            JOIN verified_builds b ON b.id=e.verified_build_id AND b.source_sha=e.requested_revision
            JOIN github_repositories r ON r.repository_id=b.repository_id AND r.policy_version=b.policy_version
                AND r.enabled AND r.full_name=e.repository_name AND r.chart_revision=b.chart_revision AND r.template_hash=b.template_hash
            WHERE e.id=? AND e.cluster_id='local' AND e.desired_state='ACTIVE' AND e.expires_at>clock_timestamp()
            """,(rs,row)->new Plan(environment,rs.getString("namespace"),rs.getString("repository_name"),
                rs.getString("requested_revision"),rs.getString("chart_revision"),rs.getString("template_hash"),
                rs.getString("api_image"),rs.getString("api_digest"),rs.getString("web_image"),rs.getString("web_digest"),
                rs.getObject("id",UUID.class),rs.getLong("policy_version")),environment).stream().findFirst();
    }

    public String namespace(UUID environment) {
        return jdbc.queryForObject("SELECT namespace FROM environments WHERE id=? AND cluster_id='local'",String.class,environment);
    }

    @Transactional
    public boolean reserve(JobLease lease) {
        // The worker locks the job/lease before the intent, consistently with finalization.
        jdbc.query("SELECT id FROM jobs WHERE id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),lease.id());
        jdbc.query("SELECT environment_id FROM environment_work_leases WHERE environment_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),lease.environmentId());
        jdbc.query("SELECT id FROM environments WHERE id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),lease.environmentId());
        if (!current(lease,true)) return false;
        jdbc.queryForObject("SELECT max_slots FROM provisioning_capacity WHERE cluster_id='local' FOR UPDATE",Integer.class);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM environment_provisioning WHERE environment_id=? AND reserved)",Boolean.class,lease.environmentId()))) return true;
        int used=jdbc.queryForObject("SELECT count(*) FROM environment_provisioning WHERE cluster_id='local' AND reserved",Integer.class);
        if (used>=settings.slots) return false;
        jdbc.update("UPDATE provisioning_capacity SET max_slots=? WHERE cluster_id='local'",settings.slots);
        jdbc.update("""
            INSERT INTO environment_provisioning(environment_id,cluster_id) VALUES (?,'local')
            ON CONFLICT(environment_id) DO UPDATE SET reserved=true,updated_at=clock_timestamp()
            """,lease.environmentId());
        return true;
    }

    public boolean current(JobLease lease, boolean active) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM jobs j JOIN environment_work_leases l ON l.environment_id=j.environment_id
            JOIN environments e ON e.id=j.environment_id
            WHERE j.id=? AND j.state='RUNNING' AND j.lease_owner=? AND j.lease_token=?
                AND j.environment_lease_token=? AND j.lease_expires_at>clock_timestamp()
                AND l.job_id=j.id AND l.worker_id=j.lease_owner AND l.fencing_token=? AND l.expires_at>clock_timestamp()
                AND e.intent_version=? AND e.desired_state=? AND (?=false OR e.expires_at>clock_timestamp()))
            """,Boolean.class,lease.id(),lease.workerId(),lease.jobToken(),lease.environmentToken(),lease.environmentToken(),
                lease.intentVersion(),active ? "ACTIVE" : "ABSENT",active));
    }

    // Invoked only inside JobStore's fenced transaction; never across Kubernetes I/O.
    public void observed(JobLease lease, Observation result) {
        boolean cleanup=lease.type().equals("TEARDOWN_ENVIRONMENT");
        if (!current(lease,!cleanup)) return;
        jdbc.update("""
            INSERT INTO environment_provisioning(environment_id,cluster_id,reserved,stage,message,observed_at)
            VALUES (?,'local',false,?,?,clock_timestamp()) ON CONFLICT(environment_id) DO UPDATE
            SET stage=excluded.stage,message=excluded.message,observed_at=clock_timestamp(),updated_at=clock_timestamp(),
                reserved=CASE WHEN ? THEN false ELSE environment_provisioning.reserved END
            """,lease.environmentId(),result.stage(),result.message(),result.removed());
        if (result.removed()) {
            int changed=jdbc.update("""
                UPDATE environments SET phase='DESTROYED',destroyed_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE id=? AND desired_state='ABSENT' AND destroyed_at IS NULL
                """,lease.environmentId());
            if (changed>0) event(lease,"CLEANUP_CONFIRMED");
        } else if (cleanup) {
            jdbc.update("UPDATE environment_provisioning SET reserved=true WHERE environment_id=?",lease.environmentId());
            jdbc.update("UPDATE environments SET phase='TERMINATING',destroyed_at=NULL,updated_at=clock_timestamp() WHERE id=? AND desired_state='ABSENT'",lease.environmentId());
        } else {
            int changed=jdbc.update("UPDATE environments SET phase='PROVISIONING',updated_at=clock_timestamp() WHERE id=? AND phase IN ('QUEUED','WAITING_BUILD')",lease.environmentId());
            if (changed>0) event(lease,"PROVISIONING_STARTED");
        }
    }

    private void event(JobLease lease,String action) {
        jdbc.update("INSERT INTO audit_events(id,environment_id,actor,action,request_id) VALUES (?,?,'kubernetes-controller',?,?)",UUID.randomUUID(),lease.environmentId(),action,lease.id().toString());
        jdbc.update("INSERT INTO outbox_events(id,aggregate_id,aggregate_version,event_type,payload) VALUES (?,?,?,?, '{}'::jsonb)",UUID.randomUUID(),lease.environmentId(),lease.intentVersion(),action);
    }

    public List<Progress> progress(String actor) {
        return jdbc.query("""
            SELECT e.id,e.namespace,p.stage,p.message,p.reserved,p.observed_at FROM environments e
            LEFT JOIN environment_provisioning p ON p.environment_id=e.id
            WHERE e.owner_actor=? ORDER BY e.created_at DESC,e.id DESC LIMIT 100
            """,(rs,row)->new Progress(rs.getObject("id",UUID.class),rs.getString("stage"),rs.getString("message"),
                rs.getString("namespace"),"ks-"+rs.getObject("id",UUID.class).toString().replace("-",""),rs.getBoolean("reserved"),
                rs.getTimestamp("observed_at")==null ? null : rs.getTimestamp("observed_at").toInstant().toString()),actor);
    }
}
