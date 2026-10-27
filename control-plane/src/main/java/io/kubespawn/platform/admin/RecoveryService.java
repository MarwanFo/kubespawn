package io.kubespawn.platform.admin;

import io.kubespawn.platform.admin.AdminModels.EnvironmentInventoryDto;
import io.kubespawn.platform.admin.AdminModels.FailedJobDto;
import io.kubespawn.platform.admin.AdminModels.RecoveryOperationDto;
import io.kubespawn.platform.admin.AdminModels.RecoveryRequest;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public RecoveryService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentInventoryDto> listEnvironmentsInventory(UUID tenantId, String status, int limit, int offset, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");

        String sql = """
            SELECT e.id, e.tenant_id, e.project_id, e.repository_name, e.requested_ref,
                   e.phase, e.desired_state, e.serving_revision, e.owner_actor,
                   e.cpu_millicores, e.memory_mb, e.storage_mb, e.expires_at, e.created_at,
                   (SELECT j.last_error FROM jobs j WHERE j.environment_id = e.id AND j.last_error IS NOT NULL ORDER BY j.updated_at DESC LIMIT 1) as last_error
            FROM environments e
            WHERE e.tenant_id = ?
        """;

        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            sql += " AND e.phase = ? ORDER BY e.created_at DESC LIMIT ? OFFSET ?";
            return jdbc.query(sql, (rs, rn) -> mapEnvironmentInventory(rs), tenantId, status.toUpperCase(), limit, offset);
        } else {
            sql += " ORDER BY e.created_at DESC LIMIT ? OFFSET ?";
            return jdbc.query(sql, (rs, rn) -> mapEnvironmentInventory(rs), tenantId, limit, offset);
        }
    }

    @Transactional(readOnly = true)
    public List<FailedJobDto> listFailedJobs(UUID tenantId, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");

        String sql = """
            SELECT j.id, j.environment_id, e.repository_name, j.job_type, j.intent_version,
                   j.state, j.attempts, j.failure_count, j.last_error, j.available_at, j.updated_at
            FROM jobs j
            JOIN environments e ON j.environment_id = e.id
            WHERE e.tenant_id = ? AND j.state = 'FAILED'
            ORDER BY j.updated_at DESC
        """;

        return jdbc.query(sql, (rs, rn) -> new FailedJobDto(
            rs.getObject("id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("repository_name"),
            rs.getString("job_type"),
            rs.getLong("intent_version"),
            rs.getString("state"),
            rs.getInt("attempts"),
            rs.getInt("failure_count"),
            rs.getString("last_error"),
            rs.getTimestamp("available_at") != null ? rs.getTimestamp("available_at").toInstant() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ), tenantId);
    }

    @Transactional
    public RecoveryOperationDto executeRecovery(UUID tenantId, UUID projectId, RecoveryRequest request, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");
        authorization.requireProjectAccess(actor, tenantId, projectId);

        if (request.reason() == null || request.reason().trim().isEmpty()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                "A mandatory audit reason is required for administrative recovery operations.");
        }

        if (request.environmentId() == null) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "ENVIRONMENT_REQUIRED",
                "Environment ID is required for recovery operations.");
        }

        // Lock environment row
        var envRows = jdbc.queryForList("""
            SELECT id, desired_state, phase, intent_version, repository_name, project_id
            FROM environments
            WHERE id = ? AND tenant_id = ?
            FOR UPDATE
        """, request.environmentId(), tenantId);

        if (envRows.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found in organization.");
        }

        var env = envRows.get(0);
        UUID envProjectId = (UUID) env.get("project_id");
        UUID effectiveProjectId = (projectId != null && !projectId.equals(new UUID(0, 0))) ? projectId : envProjectId;
        authorization.requireProjectAccess(actor, tenantId, effectiveProjectId);

        String desiredState = (String) env.get("desired_state");
        String currentPhase = (String) env.get("phase");
        long intentVersion = ((Number) env.get("intent_version")).longValue();

        String opType = request.operationType() != null ? request.operationType().toUpperCase() : "";
        if (!List.of("RETRY_OPERATION", "RECONCILE_ENVIRONMENT", "FORCE_CLEANUP").contains(opType)) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_OPERATION_TYPE",
                "Supported recovery operation types: RETRY_OPERATION, RECONCILE_ENVIRONMENT, FORCE_CLEANUP.");
        }

        // Non-reversible deletion invariant
        if ("ABSENT".equalsIgnoreCase(desiredState) && !"FORCE_CLEANUP".equals(opType)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "CANNOT_REVERSE_DELETION",
                "Environments marked for deletion (desired_state=ABSENT) cannot be reactivated or reconciled. Only FORCE_CLEANUP is permitted.");
        }

        UUID recoveryOpId = UUID.randomUUID();
        String actorName = actor != null ? actor.getName() : "admin";
        String outcomeDetails;

        switch (opType) {
            case "RETRY_OPERATION" -> {
                int resetJobs = jdbc.update("""
                    UPDATE jobs
                    SET state = 'PENDING',
                        attempts = 0,
                        failure_count = 0,
                        last_error = NULL,
                        available_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE environment_id = ? AND state = 'FAILED'
                """, request.environmentId());

                if (resetJobs == 0) {
                    UUID retryJobId = UUID.randomUUID();
                    String jobType = "ABSENT".equals(desiredState) ? "TEARDOWN_ENVIRONMENT" : "RECONCILE_ENVIRONMENT";
                    jdbc.update("""
                        INSERT INTO jobs (id, environment_id, job_type, intent_version, deduplication_key, payload, state, available_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, jsonb_build_object('environmentId', ?::text, 'intentVersion', ?::bigint), 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, retryJobId, request.environmentId(), jobType, intentVersion,
                        request.environmentId() + ":" + intentVersion + ":" + jobType + ":retry-" + System.currentTimeMillis(),
                        request.environmentId().toString(), intentVersion);
                    outcomeDetails = "Enqueued fresh retry job for " + jobType + " at intent version " + intentVersion;
                } else {
                    outcomeDetails = "Reset " + resetJobs + " failed job(s) to PENDING for immediate retry.";
                }

                String nextPhase = "ABSENT".equals(desiredState) ? "TERMINATING" : "PROVISIONING";
                jdbc.update("UPDATE environments SET phase = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", nextPhase, request.environmentId());
            }
            case "RECONCILE_ENVIRONMENT" -> {
                long nextVersion = intentVersion + 1;
                UUID reconcileJobId = UUID.randomUUID();
                jdbc.update("""
                    UPDATE environments
                    SET intent_version = ?,
                        phase = 'WAITING_BUILD',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, nextVersion, request.environmentId());

                jdbc.update("""
                    INSERT INTO jobs (id, environment_id, job_type, intent_version, deduplication_key, payload, state, available_at, created_at, updated_at)
                    VALUES (?, ?, 'RECONCILE_ENVIRONMENT', ?, ?, jsonb_build_object('environmentId', ?::text, 'intentVersion', ?::bigint), 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, reconcileJobId, request.environmentId(), nextVersion,
                    request.environmentId() + ":" + nextVersion + ":RECONCILE_ENVIRONMENT",
                    request.environmentId().toString(), nextVersion);

                outcomeDetails = "Incremented intent version to " + nextVersion + " and enqueued new RECONCILE_ENVIRONMENT job.";
            }

            case "FORCE_CLEANUP" -> {
                jdbc.update("DELETE FROM jobs WHERE environment_id = ?", request.environmentId());

                jdbc.update("""
                    UPDATE environments
                    SET desired_state = 'ABSENT',
                        phase = 'DESTROYED',
                        destroyed_at = CURRENT_TIMESTAMP,
                        intent_version = intent_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, request.environmentId());

                try {
                    jdbc.update("""
                        UPDATE usage_intervals
                        SET ended_at = clock_timestamp(),
                            duration_seconds = GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))::bigint),
                            core_hours = ROUND((cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4),
                            memory_gb_hours = ROUND((memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4),
                            estimated_cost_cents = ROUND(
                                ((ROUND((cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4) * 4.0) +
                                 (ROUND((memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4) * 0.8))
                            )::int,
                            status = 'CLOSED'
                        WHERE environment_id = ? AND status = 'ACTIVE'
                    """, request.environmentId());
                } catch (Exception ignored) {}

                outcomeDetails = "Force-cleaned environment: all jobs purged, status marked DESTROYED, and capacity released.";
            }
            default -> throw new IllegalStateException("Unexpected operation type: " + opType);
        }

        // Record in recovery_operations
        jdbc.update("""
            INSERT INTO recovery_operations (id, tenant_id, project_id, environment_id, operation_type, reason, actor, status, outcome_details, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, CURRENT_TIMESTAMP)
        """, recoveryOpId, tenantId, projectId, request.environmentId(), opType, request.reason(), actorName, outcomeDetails);

        // Record in audit_events
        jdbc.update("""
            INSERT INTO audit_events (id, tenant_id, project_id, environment_id, actor, action, request_id, details, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, jsonb_build_object(
                'operationType', ?::text,
                'reason', ?::text,
                'outcome', ?::text,
                'environmentId', ?::text,
                'recoveryOpId', ?::text
            ), CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), tenantId, projectId, request.environmentId(), actorName, "RECOVERY_" + opType,
            UUID.randomUUID().toString(), opType, request.reason(), outcomeDetails, request.environmentId().toString(), recoveryOpId.toString());

        return new RecoveryOperationDto(
            recoveryOpId,
            tenantId,
            projectId,
            request.environmentId(),
            opType,
            request.reason(),
            actorName,
            "COMPLETED",
            outcomeDetails,
            Instant.now()
        );
    }

    private EnvironmentInventoryDto mapEnvironmentInventory(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp exp = rs.getTimestamp("expires_at");
        Timestamp cr = rs.getTimestamp("created_at");
        return new EnvironmentInventoryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("repository_name"),
            rs.getString("requested_ref"),
            rs.getString("phase"),
            rs.getString("desired_state"),
            rs.getString("serving_revision"),
            rs.getString("owner_actor"),
            rs.getInt("cpu_millicores"),
            rs.getInt("memory_mb"),
            rs.getInt("storage_mb"),
            exp != null ? exp.toInstant() : null,
            cr != null ? cr.toInstant() : null,
            rs.getString("last_error")
        );
    }
}
