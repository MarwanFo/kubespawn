package io.kubespawn.platform.admin;

import io.kubespawn.platform.admin.AdminModels.AuditEventDto;
import io.kubespawn.platform.admin.AdminModels.AuditPageDto;
import io.kubespawn.platform.admin.AdminModels.EnvironmentInventoryDto;
import io.kubespawn.platform.admin.AdminModels.FailedJobDto;
import io.kubespawn.platform.admin.AdminModels.IntegrationHealthDto;
import io.kubespawn.platform.admin.AdminModels.ProjectQuotaDto;
import io.kubespawn.platform.admin.AdminModels.RecoveryOperationDto;
import io.kubespawn.platform.admin.AdminModels.RecoveryRequest;
import io.kubespawn.platform.admin.AdminModels.TenantQuotaDto;
import io.kubespawn.platform.admin.AdminModels.UpdateQuotaRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class AdminController {

    private final QuotaService quotaService;
    private final RecoveryService recoveryService;
    private final AuditSearchService auditSearchService;
    private final JdbcTemplate jdbc;
    private final Environment env;

    public AdminController(
            QuotaService quotaService,
            RecoveryService recoveryService,
            AuditSearchService auditSearchService,
            JdbcTemplate jdbc,
            Environment env) {
        this.quotaService = quotaService;
        this.recoveryService = recoveryService;
        this.auditSearchService = auditSearchService;
        this.jdbc = jdbc;
        this.env = env;
    }

    // 1. Quota Management
    @GetMapping("/quotas")
    public TenantQuotaDto getTenantQuotas(
            @PathVariable UUID tenantId,
            Principal actor) {
        return quotaService.getTenantQuotas(tenantId, actor);
    }

    @PutMapping("/quotas")
    public TenantQuotaDto updateTenantQuotas(
            @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateQuotaRequest request,
            Principal actor) {
        return quotaService.updateTenantQuotas(tenantId, request, actor);
    }

    @GetMapping("/projects/{projectId}/quotas")
    public ProjectQuotaDto getProjectQuotas(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            Principal actor) {
        return quotaService.getProjectQuotas(tenantId, projectId, actor);
    }

    @PutMapping("/projects/{projectId}/quotas")
    public ProjectQuotaDto updateProjectQuotas(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateQuotaRequest request,
            Principal actor) {
        return quotaService.updateProjectQuotas(tenantId, projectId, request, actor);
    }

    // 2. Inventory & Failed Operations
    @GetMapping("/inventory")
    public List<EnvironmentInventoryDto> listInventory(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            Principal actor) {
        return recoveryService.listEnvironmentsInventory(tenantId, status, limit, offset, actor);
    }

    @GetMapping("/failed-jobs")
    public List<FailedJobDto> listFailedJobs(
            @PathVariable UUID tenantId,
            Principal actor) {
        return recoveryService.listFailedJobs(tenantId, actor);
    }

    // 3. Approved Recovery Operations
    @PostMapping("/projects/{projectId}/recovery")
    public ResponseEntity<RecoveryOperationDto> executeRecovery(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @Valid @RequestBody RecoveryRequest request,
            Principal actor) {
        var op = recoveryService.executeRecovery(tenantId, projectId, request, actor);
        return ResponseEntity.status(HttpStatus.OK).body(op);
    }

    // 4. Searchable Scoped Audit History
    @GetMapping("/audit")
    public AuditPageDto searchAudit(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorFilter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            Principal actor) {
        return auditSearchService.searchAudit(tenantId, projectId, action, actorFilter, from, to, limit, offset, actor);
    }

    // 5. Integration Health
    @GetMapping("/health")
    public IntegrationHealthDto checkHealth(
            @PathVariable UUID tenantId,
            Principal actor) {
        boolean dbOk = false;
        try {
            Integer res = jdbc.queryForObject("SELECT 1", Integer.class);
            dbOk = (res != null && res == 1);
        } catch (Exception ignored) {
        }

        boolean workerActive = Boolean.TRUE.equals(env.getProperty("kubespawn.worker.enabled", Boolean.class, true));
        boolean k8sConfigured = Boolean.TRUE.equals(env.getProperty("kubespawn.kubernetes.enabled", Boolean.class, false))
                || System.getenv("KUBECONFIG") != null;
        boolean githubConfigured = Boolean.TRUE.equals(env.getProperty("kubespawn.github.enabled", Boolean.class, false));
        String authMode = env.getProperty("kubespawn.auth.mode", "oidc");

        return new IntegrationHealthDto(
            dbOk,
            workerActive,
            k8sConfigured,
            true, // Preview routing ready (Traefik edge)
            githubConfigured,
            authMode,
            Instant.now()
        );
    }
}
