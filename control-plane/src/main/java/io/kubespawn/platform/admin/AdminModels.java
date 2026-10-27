package io.kubespawn.platform.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AdminModels {

    public record QuotaConsumption(
        int activeEnvironments,
        int queuedEnvironments,
        int usedCpuMillicores,
        int usedMemoryMb,
        int usedStorageMb
    ) {}

    public record TenantQuotaDto(
        UUID tenantId,
        int maxEnvironments,
        int maxQueuedEnvironments,
        int cpuMillicoresQuota,
        int memoryMbQuota,
        int storageMbQuota,
        int maxTtlHours,
        int policyVersion,
        Instant updatedAt,
        String updatedBy,
        QuotaConsumption consumption
    ) {}

    public record ProjectQuotaDto(
        UUID projectId,
        UUID tenantId,
        int maxEnvironments,
        int cpuMillicoresQuota,
        int memoryMbQuota,
        int storageMbQuota,
        int maxTtlHours,
        int policyVersion,
        Instant updatedAt,
        String updatedBy,
        QuotaConsumption consumption
    ) {}

    public record UpdateQuotaRequest(
        int maxEnvironments,
        Integer maxQueuedEnvironments,
        int cpuMillicoresQuota,
        int memoryMbQuota,
        Integer storageMbQuota,
        int maxTtlHours,
        int policyVersion
    ) {}

    public record EnvironmentInventoryDto(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String repositoryName,
        String requestedRef,
        String phase,
        String desiredState,
        String servingRevision,
        String ownerActor,
        int cpuMillicores,
        int memoryMb,
        int storageMb,
        Instant expiresAt,
        Instant createdAt,
        String lastError
    ) {}

    public record FailedJobDto(
        UUID id,
        UUID environmentId,
        String repositoryName,
        String jobType,
        long intentVersion,
        String state,
        int attempts,
        int failureCount,
        String lastError,
        Instant availableAt,
        Instant updatedAt
    ) {}

    public record RecoveryRequest(
        String operationType,
        String reason,
        UUID environmentId
    ) {}

    public record RecoveryOperationDto(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID environmentId,
        String operationType,
        String reason,
        String actor,
        String status,
        String outcomeDetails,
        Instant createdAt
    ) {}

    public record AuditEventDto(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID environmentId,
        String actor,
        String action,
        String requestId,
        String details,
        Instant createdAt
    ) {}

    public record AuditPageDto(
        List<AuditEventDto> items,
        int total,
        int limit,
        int offset
    ) {}

    public record IntegrationHealthDto(
        boolean databaseOk,
        boolean workerActive,
        boolean kubernetesConfigured,
        boolean previewRoutingReady,
        boolean githubConfigured,
        String authMode,
        Instant checkedAt
    ) {}
}
