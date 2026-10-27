package io.kubespawn.platform.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UsageModels {

    private UsageModels() {}

    public record PlanDto(
        String id,
        String name,
        String tier,
        String description,
        int maxEnvironments,
        int maxQueuedEnvironments,
        int cpuMillicoresQuota,
        int memoryMbQuota,
        int storageMbQuota,
        int maxTtlHours,
        int priceCentsMonthly,
        double cpuRateCentsPerCoreHour,
        double memoryRateCentsPerGbHour,
        List<String> features,
        boolean isActive,
        int version
    ) {}

    public record TenantSubscriptionDto(
        UUID tenantId,
        String planId,
        String planName,
        String tier,
        int planVersion,
        String status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        String billingProvider,
        boolean cancelAtPeriodEnd
    ) {}

    public record ChangePlanRequest(
        String planId
    ) {}

    public record UsageIntervalDto(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID environmentId,
        String environmentName,
        int cpuMillicores,
        int memoryMb,
        int storageMb,
        Instant startedAt,
        Instant endedAt,
        Long durationSeconds,
        double coreHours,
        double memoryGbHours,
        int estimatedCostCents,
        String status
    ) {}

    public record ProjectUsageSummaryDto(
        UUID projectId,
        String projectName,
        int activeEnvironments,
        double totalCoreHours,
        double totalMemoryGbHours,
        int estimatedCostCents
    ) {}

    public record UsageSummaryDto(
        UUID tenantId,
        TenantSubscriptionDto subscription,
        PlanDto plan,
        int activeEnvironments,
        int activeEnvironmentsQuota,
        int usedCpuMillicores,
        int cpuMillicoresQuota,
        int usedMemoryMb,
        int memoryMbQuota,
        int usedStorageMb,
        int storageMbQuota,
        double periodCoreHours,
        double periodMemoryGbHours,
        int estimatedPeriodCostCents,
        Instant periodStart,
        Instant periodEnd,
        List<ProjectUsageSummaryDto> projectBreakdown
    ) {}

    public record BillingEventRequest(
        String idempotencyKey,
        UUID tenantId,
        String eventType,
        String provider,
        String payload
    ) {}

    public record BillingEventDto(
        UUID id,
        String idempotencyKey,
        UUID tenantId,
        String eventType,
        String provider,
        String status,
        Instant processedAt
    ) {}

    public record UsageIntervalsPageDto(
        List<UsageIntervalDto> items,
        int page,
        int size,
        long total
    ) {}
}
