package io.kubespawn.platform.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.admin.AdminModels.AuditEventDto;
import io.kubespawn.platform.admin.AdminModels.AuditPageDto;
import io.kubespawn.platform.admin.AdminModels.EnvironmentInventoryDto;
import io.kubespawn.platform.admin.AdminModels.FailedJobDto;
import io.kubespawn.platform.admin.AdminModels.ProjectQuotaDto;
import io.kubespawn.platform.admin.AdminModels.QuotaConsumption;
import io.kubespawn.platform.admin.AdminModels.RecoveryOperationDto;
import io.kubespawn.platform.admin.AdminModels.RecoveryRequest;
import io.kubespawn.platform.admin.AdminModels.TenantQuotaDto;
import io.kubespawn.platform.admin.AdminModels.UpdateQuotaRequest;
import io.kubespawn.platform.api.ApiErrors;
import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import({SecurityConfig.class, ApiErrors.class})
@ActiveProfiles("local")
class AdminControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean QuotaService quotaService;
    @MockitoBean RecoveryService recoveryService;
    @MockitoBean AuditSearchService auditSearchService;
    @MockitoBean JdbcTemplate jdbc;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private final UUID envId = UUID.fromString("10000000-0000-0000-0000-000000000032");

    @Test
    void adminCanReadTenantQuotas() throws Exception {
        var consumption = new QuotaConsumption(2, 0, 2000, 4096, 10240);
        var quota = new TenantQuotaDto(
            tenantId, 5, 10, 8000, 16384, 51200, 72, 1, Instant.now(), "system", consumption
        );

        when(quotaService.getTenantQuotas(eq(tenantId), any())).thenReturn(quota);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/quotas")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.maxEnvironments").value(5))
            .andExpect(jsonPath("$.cpuMillicoresQuota").value(8000))
            .andExpect(jsonPath("$.consumption.activeEnvironments").value(2))
            .andExpect(jsonPath("$.consumption.usedCpuMillicores").value(2000));
    }

    @Test
    void adminCanUpdateTenantQuotas() throws Exception {
        var consumption = new QuotaConsumption(2, 0, 2000, 4096, 10240);
        var updated = new TenantQuotaDto(
            tenantId, 10, 15, 16000, 32768, 102400, 120, 2, Instant.now(), "local-developer", consumption
        );

        when(quotaService.updateTenantQuotas(eq(tenantId), any(UpdateQuotaRequest.class), any())).thenReturn(updated);

        String payload = """
            {
                "maxEnvironments": 10,
                "maxQueuedEnvironments": 15,
                "cpuMillicoresQuota": 16000,
                "memoryMbQuota": 32768,
                "storageMbQuota": 102400,
                "maxTtlHours": 120,
                "policyVersion": 1
            }
            """;

        mvc.perform(put("/api/v1/tenants/" + tenantId + "/quotas")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxEnvironments").value(10))
            .andExpect(jsonPath("$.policyVersion").value(2));
    }

    @Test
    void updateTenantQuotasRejectsConcurrentModification() throws Exception {
        when(quotaService.updateTenantQuotas(eq(tenantId), any(UpdateQuotaRequest.class), any()))
            .thenThrow(new LifecycleException(HttpStatus.CONFLICT, "CONCURRENT_POLICY_MODIFICATION",
                "Tenant quota policy was modified concurrently. Current version is 2. Please refresh before updating."));

        String payload = """
            {
                "maxEnvironments": 8,
                "cpuMillicoresQuota": 10000,
                "memoryMbQuota": 20480,
                "maxTtlHours": 72,
                "policyVersion": 1
            }
            """;

        mvc.perform(put("/api/v1/tenants/" + tenantId + "/quotas")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("CONCURRENT_POLICY_MODIFICATION"));
    }

    @Test
    void adminCanReadProjectQuotas() throws Exception {
        var consumption = new QuotaConsumption(1, 0, 1000, 2048, 5120);
        var quota = new ProjectQuotaDto(
            projectId, tenantId, 4, 6000, 12288, 30720, 72, 1, Instant.now(), "system", consumption
        );

        when(quotaService.getProjectQuotas(eq(tenantId), eq(projectId), any())).thenReturn(quota);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/quotas")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.maxEnvironments").value(4))
            .andExpect(jsonPath("$.cpuMillicoresQuota").value(6000));
    }

    @Test
    void adminCanListEnvironmentInventory() throws Exception {
        var env = new EnvironmentInventoryDto(
            envId, tenantId, projectId, "acme/inventory-service", "fix/stock-sync",
            "FAILED", "ACTIVE", null, "acme-dev",
            1000, 2048, 5120, Instant.now().plusSeconds(3600), Instant.now().minusSeconds(7200),
            "Argo CD application reconciliation timed out after 3 retries."
        );

        when(recoveryService.listEnvironmentsInventory(eq(tenantId), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of(env));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/inventory")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(envId.toString()))
            .andExpect(jsonPath("$[0].repositoryName").value("acme/inventory-service"))
            .andExpect(jsonPath("$[0].phase").value("FAILED"))
            .andExpect(jsonPath("$[0].lastError").value("Argo CD application reconciliation timed out after 3 retries."));
    }

    @Test
    void adminCanListFailedJobs() throws Exception {
        var failedJob = new FailedJobDto(
            UUID.randomUUID(), envId, "acme/inventory-service", "PROVISION_ENVIRONMENT",
            1L, "FAILED", 3, 3, "Argo CD application reconciliation timed out after 3 retries.",
            Instant.now(), Instant.now()
        );

        when(recoveryService.listFailedJobs(eq(tenantId), any())).thenReturn(List.of(failedJob));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/failed-jobs")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].environmentId").value(envId.toString()))
            .andExpect(jsonPath("$[0].state").value("FAILED"))
            .andExpect(jsonPath("$[0].failureCount").value(3));
    }

    @Test
    void adminCanExecuteRecoveryOperation() throws Exception {
        UUID opId = UUID.randomUUID();
        var op = new RecoveryOperationDto(
            opId, tenantId, projectId, envId, "RETRY_OPERATION",
            "Manual recovery after cluster transient glitch", "local-developer",
            "COMPLETED", "Reset 1 failed job(s) to AVAILABLE for immediate retry.", Instant.now()
        );

        when(recoveryService.executeRecovery(eq(tenantId), eq(projectId), any(RecoveryRequest.class), any()))
            .thenReturn(op);

        String payload = """
            {
                "operationType": "RETRY_OPERATION",
                "reason": "Manual recovery after cluster transient glitch",
                "environmentId": "%s"
            }
            """.formatted(envId);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/recovery")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(opId.toString()))
            .andExpect(jsonPath("$.operationType").value("RETRY_OPERATION"))
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void recoveryRejectsAbsentEnvironmentReactivation() throws Exception {
        when(recoveryService.executeRecovery(eq(tenantId), eq(projectId), any(RecoveryRequest.class), any()))
            .thenThrow(new LifecycleException(HttpStatus.CONFLICT, "CANNOT_REVERSE_DELETION",
                "Environments marked for deletion (desired_state=ABSENT) cannot be reactivated or reconciled. Only FORCE_CLEANUP is permitted."));

        String payload = """
            {
                "operationType": "RECONCILE_ENVIRONMENT",
                "reason": "Accidental deletion recovery attempt",
                "environmentId": "%s"
            }
            """.formatted(envId);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/recovery")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("CANNOT_REVERSE_DELETION"));
    }

    @Test
    void adminCanSearchAuditHistory() throws Exception {
        var item = new AuditEventDto(
            UUID.randomUUID(), tenantId, projectId, envId, "local-developer",
            "RECOVERY_RETRY_OPERATION", "req-123", "{\"reason\":\"retry\"}", Instant.now()
        );
        var page = new AuditPageDto(List.of(item), 1, 50, 0);

        when(auditSearchService.searchAudit(eq(tenantId), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(page);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/audit")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].action").value("RECOVERY_RETRY_OPERATION"));
    }

    @Test
    void healthProbeReturnsIntegrationStatus() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/health")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.databaseOk").value(true))
            .andExpect(jsonPath("$.previewRoutingReady").value(true));
    }
}
