package io.kubespawn.platform.lifecycle;

import io.kubespawn.platform.api.EnvironmentSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifecycleService {
    private final JdbcTemplate jdbc;

    public LifecycleService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Mutation(EnvironmentSummary environment, UUID operationId) {}
    public record Operation(UUID id, UUID environmentId, String type, String state, long intentVersion,
            int attempts, Instant createdAt) {}
    public record AuditEvent(UUID id, String action, String actor, String requestId, Instant createdAt) {}
    private record Claim(String fingerprint, UUID environmentId, UUID operationId) {}
    private record Owned(EnvironmentSummary summary, UUID sourceId, String owner) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Mutation create(CreateEnvironmentRequest request, UUID key, String actor, String requestId) {
        String repository = request.repository().toLowerCase(Locale.ROOT);
        String fingerprint = fingerprint(repository + "\0" + request.ref() + "\0" + request.ttlHours());
        // ON CONFLICT waits for a concurrent claim; a subsequent READ COMMITTED statement sees its result.
        jdbc.update("""
            INSERT INTO api_idempotency(actor, idempotency_key, request_fingerprint)
            VALUES (?, ?, ?) ON CONFLICT (actor, idempotency_key) DO NOTHING
            """, actor, key, fingerprint);
        Claim claim = jdbc.queryForObject("""
            SELECT request_fingerprint, environment_id, operation_id FROM api_idempotency
            WHERE actor=? AND idempotency_key=? FOR UPDATE
            """, (rs, row) -> new Claim(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), actor, key);
        if (!fingerprint.equals(claim.fingerprint())) {
            throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This request key was already used with different values.");
        }
        if (claim.environmentId() != null) {
            return new Mutation(owned(claim.environmentId(), actor, false).summary(), claim.operationId());
        }

        Instant now = databaseNow();
        UUID source = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        String namespace = "ks-man-" + environment.toString().replace("-", "");
        jdbc.update("""
            INSERT INTO environment_sources(id, tenant_key, project_key, repository_name,
                source_kind, manual_request_id, requested_ref, source_state, created_at, updated_at)
            VALUES (?, 'local', 'manual', ?, 'MANUAL', ?, ?, 'MANUAL', ?, ?)
            """, source, repository, environment, request.ref(), timestamp(now), timestamp(now));
        jdbc.update("""
            INSERT INTO environments(id, source_id, repository_name, cluster_id, namespace, owner_actor,
                requested_ref, desired_state, phase, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, 'local', ?, ?, ?, 'ACTIVE', 'WAITING_BUILD', ?, ?, ?)
            """, environment, source, repository, namespace, actor, request.ref(),
            timestamp(now.plus(Duration.ofHours(request.ttlHours()))), timestamp(now), timestamp(now));
        UUID operation = recordIntent(environment, 0, "RECONCILE_ENVIRONMENT", "ENVIRONMENT_REQUESTED", actor, requestId);
        jdbc.update("UPDATE api_idempotency SET environment_id=?, operation_id=? WHERE actor=? AND idempotency_key=?",
            environment, operation, actor, key);
        return new Mutation(owned(environment, actor, false).summary(), operation);
    }

    @Transactional(readOnly = true)
    public EnvironmentSummary get(UUID id, String actor) { return owned(id, actor, false).summary(); }

    @Transactional
    public Mutation extend(UUID id, long version, Instant newExpiry, String actor, String requestId) {
        Owned environment = owned(id, actor, true);
        EnvironmentSummary current = environment.summary();
        requireActive(current);
        requireVersion(current, version);
        Instant now = databaseNow();
        if (!current.expiresAt().isAfter(now)) {
            throw failure(HttpStatus.CONFLICT, "ENVIRONMENT_EXPIRED", "An expired request cannot be extended.");
        }
        if (!newExpiry.isAfter(current.expiresAt()) || newExpiry.isAfter(current.createdAt().plus(Duration.ofHours(72)))) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EXPIRY", "Choose a later expiry within 72 hours of creation.");
        }
        jdbc.update("UPDATE environments SET expires_at=?, intent_version=intent_version+1, updated_at=? WHERE id=?",
            timestamp(newExpiry), timestamp(now), id);
        UUID operation = recordIntent(id, version + 1, "RECONCILE_ENVIRONMENT", "EXPIRY_EXTENDED", actor, requestId);
        return new Mutation(owned(id, actor, false).summary(), operation);
    }

    @Transactional
    public Mutation delete(UUID id, long version, String actor, String requestId) {
        Owned environment = owned(id, actor, true);
        if (environment.summary().desiredState().equals("ABSENT")) {
            UUID operation = jdbc.query("""
                SELECT id FROM jobs WHERE environment_id=? AND job_type='TEARDOWN_ENVIRONMENT'
                ORDER BY intent_version DESC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), id).stream().findFirst().orElseThrow(() ->
                    failure(HttpStatus.CONFLICT, "NO_TEARDOWN_OPERATION", "This historical request has no tracked teardown operation."));
            return new Mutation(environment.summary(), operation);
        }
        requireVersion(environment.summary(), version);
        return requestAbsence(environment, actor, requestId, "DELETION_REQUESTED", "MANUAL_DELETE");
    }

    @Transactional
    public int expireDueBatch() {
        List<UUID> expired = jdbc.query("""
            SELECT id FROM environments WHERE desired_state='ACTIVE' AND expires_at <= clock_timestamp()
            ORDER BY expires_at, id LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, row) -> rs.getObject(1, UUID.class));
        for (UUID id : expired) {
            Owned environment = load(id, true);
            requestAbsence(environment, "system-expiry", UUID.randomUUID().toString(), "TTL_EXPIRED", "TTL_EXPIRED");
        }
        return expired.size();
    }

    @Transactional(readOnly = true)
    public Operation operation(UUID id, String actor) {
        return jdbc.query("""
            SELECT j.* FROM jobs j JOIN environments e ON e.id=j.environment_id
            WHERE j.id=? AND e.owner_actor=?
            """, (rs, row) -> new Operation(rs.getObject("id", UUID.class), rs.getObject("environment_id", UUID.class),
                rs.getString("job_type"), rs.getString("state"), rs.getLong("intent_version"),
                rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant()), id, actor)
            .stream().findFirst().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Operation not found."));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> audit(UUID id, String actor) {
        owned(id, actor, false);
        return jdbc.query("""
            SELECT id, action, actor, request_id, created_at FROM audit_events WHERE environment_id=?
            ORDER BY created_at DESC, id DESC LIMIT 50
            """, (rs, row) -> new AuditEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant()), id);
    }

    private Mutation requestAbsence(Owned environment, String actor, String requestId, String action, String reason) {
        EnvironmentSummary current = environment.summary();
        Instant now = databaseNow();
        jdbc.update("""
            UPDATE environments SET desired_state='ABSENT', phase='TERMINATING',
                intent_version=intent_version+1, updated_at=? WHERE id=?
            """, timestamp(now), current.id());
        jdbc.update("UPDATE environment_sources SET suppression_reason=?, updated_at=? WHERE id=?",
            reason, timestamp(now), environment.sourceId());
        UUID operation = recordIntent(current.id(), current.intentVersion() + 1, "TEARDOWN_ENVIRONMENT", action, actor, requestId);
        return new Mutation(load(current.id(), false).summary(), operation);
    }

    private UUID recordIntent(UUID environment, long version, String type, String action, String actor, String requestId) {
        UUID operation = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO jobs(id, environment_id, job_type, intent_version, deduplication_key, payload)
            VALUES (?, ?, ?, ?, ?, jsonb_build_object('environmentId', ?::text, 'intentVersion', ?::bigint))
            """, operation, environment, type, version, environment + ":" + version + ":" + type,
            environment.toString(), version);
        jdbc.update("""
            INSERT INTO audit_events(id, environment_id, actor, action, request_id, details, created_at)
            SELECT ?, id, ?, ?, ?, jsonb_build_object('intentVersion', ?::bigint, 'operationId', ?::text,
                'desiredState', desired_state, 'phase', phase, 'expiresAt', expires_at,
                'repository', repository_name, 'requestedRef', requested_ref), clock_timestamp()
            FROM environments WHERE id=?
            """, UUID.randomUUID(), actor, action, requestId, version, operation.toString(), environment);
        jdbc.update("""
            INSERT INTO outbox_events(id, aggregate_id, aggregate_version, event_type, payload)
            VALUES (?, ?, ?, ?, jsonb_build_object('environmentId', ?::text, 'operationId', ?::text, 'intentVersion', ?::bigint))
            """, UUID.randomUUID(), environment, version, action, environment.toString(), operation.toString(), version);
        return operation;
    }

    private Owned owned(UUID id, String actor, boolean lock) {
        Owned environment = load(id, lock);
        if (!environment.owner().equals(actor)) {
            throw failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found.");
        }
        return environment;
    }

    private Owned load(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM environments WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Owned(summary(rs), rs.getObject("source_id", UUID.class), rs.getString("owner_actor")), id)
            .stream().findFirst().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found."));
    }

    private EnvironmentSummary summary(ResultSet rs) throws SQLException {
        return new EnvironmentSummary(rs.getObject("id", UUID.class), rs.getString("repository_name"),
            rs.getString("namespace"), rs.getString("desired_state"), rs.getString("phase"),
            rs.getString("requested_revision"), rs.getString("serving_revision"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            rs.getString("requested_ref"), rs.getLong("intent_version"));
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static void requireActive(EnvironmentSummary environment) {
        if (!environment.desiredState().equals("ACTIVE")) {
            throw failure(HttpStatus.CONFLICT, "DELETION_ALREADY_REQUESTED", "Deletion was already requested; this generation cannot be reactivated.");
        }
    }

    private static void requireVersion(EnvironmentSummary environment, long version) {
        if (environment.intentVersion() != version) {
            throw failure(HttpStatus.PRECONDITION_FAILED, "STALE_VERSION", "This request changed. Refresh it before trying again.");
        }
    }

    private static String fingerprint(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static LifecycleException failure(HttpStatus status, String code, String message) {
        return new LifecycleException(status, code, message);
    }
}
