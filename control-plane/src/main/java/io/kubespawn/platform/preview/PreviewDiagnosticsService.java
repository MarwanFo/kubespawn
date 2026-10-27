package io.kubespawn.platform.preview;

import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.provisioning.ProvisioningGateway;
import io.kubespawn.platform.provisioning.ProvisioningModels;
import io.kubespawn.platform.provisioning.ProvisioningSettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreviewDiagnosticsService {
    public record ConditionDto(String type, String status, String reason, String message) {}

    public record DiagnosticsDto(
        UUID environmentId,
        UUID tenantId,
        UUID projectId,
        String repository,
        String requestedRef,
        String namespace,
        String phase,
        String desiredState,
        String servingRevision,
        Instant createdAt,
        Instant expiresAt,
        boolean isExpired,
        String previewUrl,
        boolean previewRoutingReady,
        String stage,
        String stageMessage,
        List<ConditionDto> conditions,
        List<String> recentLogs
    ) {}

    private final JdbcTemplate jdbc;
    private final ProvisioningSettings settings;
    private final ProvisioningGateway gateway;

    public PreviewDiagnosticsService(JdbcTemplate jdbc, ProvisioningSettings settings, ProvisioningGateway gateway) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.gateway = gateway;
    }

    @Transactional(readOnly = true)
    public DiagnosticsDto getDiagnostics(UUID tenantId, UUID projectId, UUID environmentId) {
        var envRows = jdbc.query("""
            SELECT e.id, e.tenant_id, e.project_id, e.repository_name, e.requested_ref,
                   e.namespace, e.phase, e.desired_state, e.serving_revision,
                   e.created_at, e.expires_at,
                   p.stage, p.message, p.reserved, p.observed_at
            FROM environments e
            LEFT JOIN environment_provisioning p ON p.environment_id = e.id
            WHERE e.id = ? AND e.tenant_id = ? AND e.project_id = ?
            """, (rs, row) -> {
                boolean expired = rs.getTimestamp("expires_at") != null &&
                    rs.getTimestamp("expires_at").toInstant().isBefore(Instant.now());
                String phase = rs.getString("phase");
                String stage = rs.getString("stage") != null ? rs.getString("stage") : phase;
                String stageMessage = rs.getString("message") != null ? rs.getString("message") : "Processing lifecycle intent.";

                List<ConditionDto> conditions = new ArrayList<>();
                boolean isReady = "READY".equals(phase);
                boolean isProvisioning = "PROVISIONING".equals(phase) || isReady;
                boolean isWaitingBuild = "WAITING_BUILD".equals(phase) || isProvisioning;

                conditions.add(new ConditionDto("BuildVerified", isProvisioning ? "True" : isWaitingBuild ? "Pending" : "False",
                    isProvisioning ? "BuildArtifactSigned" : "WaitingForWorkflowArtifact",
                    "GitHub container workflow artifact verification."));

                conditions.add(new ConditionDto("WorkloadProvisioned", isReady ? "True" : isProvisioning ? "In_Progress" : "False",
                    isReady ? "PodsRunningAndHealthy" : "ReconcilingArgoResources",
                    "Ephemeral preview deployment and application pods."));

                conditions.add(new ConditionDto("RouteProgrammed", isReady && gateway.previewRoutingReady() ? "True" : "Pending",
                    gateway.previewRoutingReady() ? "EnvoyHttpRouteAccepted" : "ConfiguringGatewayRoute",
                    "Path-scoped Envoy Gateway route programming."));

                conditions.add(new ConditionDto("EdgeProbePassed", isReady ? "True" : "False",
                    isReady ? "AuthenticatedProbeSuccess" : "AwaitingPrivateEdgeVerification",
                    "Cryptographic preview edge authentication probe."));

                // Fetch recent audit logs for this environment
                List<String> logs = jdbc.query("""
                    SELECT action, actor, created_at FROM audit_events
                    WHERE environment_id = ? ORDER BY created_at DESC LIMIT 10
                    """, (lrs, lrow) -> String.format("%s [%s] %s",
                        lrs.getTimestamp("created_at").toInstant().toString(),
                        lrs.getString("actor"),
                        lrs.getString("action")), environmentId);

                return new DiagnosticsDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("repository_name"),
                    rs.getString("requested_ref"),
                    rs.getString("namespace"),
                    phase,
                    rs.getString("desired_state"),
                    rs.getString("serving_revision"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    expired,
                    isReady ? settings.previewUrl(environmentId) : null,
                    gateway.previewRoutingReady(),
                    stage,
                    stageMessage,
                    conditions,
                    logs
                );
            }, environmentId, tenantId, projectId);

        return envRows.stream().findFirst().orElseThrow(() ->
            new LifecycleException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found in project."));
    }

    @Transactional(readOnly = true)
    public List<ProvisioningModels.Progress> getProjectProgress(UUID tenantId, UUID projectId) {
        return jdbc.query("""
            SELECT e.id, e.namespace, e.phase, p.stage, p.message, p.reserved, p.observed_at
            FROM environments e
            LEFT JOIN environment_provisioning p ON p.environment_id = e.id
            WHERE e.tenant_id = ? AND e.project_id = ?
            ORDER BY e.created_at DESC, e.id DESC LIMIT 100
            """, (rs, row) -> new ProvisioningModels.Progress(
                rs.getObject("id", UUID.class),
                rs.getString("stage"),
                rs.getString("message"),
                rs.getString("namespace"),
                "ks-" + rs.getObject("id", UUID.class).toString().replace("-", ""),
                rs.getBoolean("reserved"),
                rs.getTimestamp("observed_at") == null ? null : rs.getTimestamp("observed_at").toInstant().toString(),
                "READY".equals(rs.getString("phase")) ? settings.previewUrl(rs.getObject("id", UUID.class)) : null
            ), tenantId, projectId);
    }
}
