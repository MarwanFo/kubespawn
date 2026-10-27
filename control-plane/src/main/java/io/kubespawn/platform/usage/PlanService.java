package io.kubespawn.platform.usage;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.usage.UsageModels.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final UsageService usageService;

    @Autowired
    public PlanService(JdbcTemplate jdbc, AuthorizationService authorization, UsageService usageService) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.usageService = usageService;
    }

    @Transactional(readOnly = true)
    public List<PlanDto> listAvailablePlans() {
        return usageService.listPlans();
    }

    @Transactional
    public TenantSubscriptionDto getTenantPlan(UUID tenantId, Principal actor) {
        authorization.requireRole(actor, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
        return usageService.fetchOrCreateSubscription(tenantId);
    }

    @Transactional
    public TenantSubscriptionDto changePlan(UUID tenantId, ChangePlanRequest req, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");

        if (req.planId() == null || req.planId().isBlank()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_PLAN", "Plan ID cannot be empty.");
        }

        PlanDto newPlan = usageService.fetchPlan(req.planId().toUpperCase());
        TenantSubscriptionDto currentSub = usageService.fetchOrCreateSubscription(tenantId);

        String actorName = actor != null ? actor.getName() : "admin";

        // Update subscription
        jdbc.update("""
            UPDATE tenant_subscriptions
            SET plan_id = ?,
                plan_version = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ?
        """, newPlan.id(), newPlan.version(), tenantId);

        // Synchronize tenant quotas with new plan limits
        jdbc.update("""
            INSERT INTO tenant_quotas (
                tenant_id, max_environments, max_queued_environments,
                cpu_millicores_quota, memory_mb_quota, storage_mb_quota,
                max_ttl_hours, policy_version, updated_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT (tenant_id) DO UPDATE SET
                max_environments = EXCLUDED.max_environments,
                max_queued_environments = EXCLUDED.max_queued_environments,
                cpu_millicores_quota = EXCLUDED.cpu_millicores_quota,
                memory_mb_quota = EXCLUDED.memory_mb_quota,
                storage_mb_quota = EXCLUDED.storage_mb_quota,
                max_ttl_hours = EXCLUDED.max_ttl_hours,
                policy_version = tenant_quotas.policy_version + 1,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = EXCLUDED.updated_by
        """, tenantId, newPlan.maxEnvironments(), newPlan.maxQueuedEnvironments(),
             newPlan.cpuMillicoresQuota(), newPlan.memoryMbQuota(), newPlan.storageMbQuota(),
             newPlan.maxTtlHours(), actorName);

        // Immutably audit plan mutation
        jdbc.update("""
            INSERT INTO audit_events (id, tenant_id, actor, action, request_id, details, created_at)
            VALUES (?, ?, ?, 'PLAN_CHANGED', ?, jsonb_build_object(
                'previousPlan', ?, 'newPlan', ?, 'newLimits', jsonb_build_object(
                    'maxEnvironments', ?::int, 'cpuMillicores', ?::int, 'memoryMb', ?::int, 'maxTtlHours', ?::int
                )
            ), CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), tenantId, actorName, UUID.randomUUID().toString(),
             currentSub.planId(), newPlan.id(), newPlan.maxEnvironments(), newPlan.cpuMillicoresQuota(),
             newPlan.memoryMbQuota(), newPlan.maxTtlHours());

        return usageService.fetchOrCreateSubscription(tenantId);
    }

    @Transactional
    public BillingEventDto processBillingEvent(BillingEventRequest req) {
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "Idempotency key is required for billing events.");
        }

        // Replay check
        var existing = jdbc.query("""
            SELECT id, idempotency_key, tenant_id, event_type, provider, status, processed_at
            FROM billing_events WHERE idempotency_key = ?
        """, (rs, rn) -> new BillingEventDto(
            (UUID) rs.getObject("id"),
            rs.getString("idempotency_key"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("event_type"),
            rs.getString("provider"),
            rs.getString("status"),
            rs.getTimestamp("processed_at").toInstant()
        ), req.idempotencyKey());

        if (!existing.isEmpty()) {
            return existing.get(0); // Replay deduplication: idempotent success
        }

        UUID eventId = UUID.randomUUID();
        String provider = req.provider() != null ? req.provider() : "INTERNAL";

        // Handle specific event types
        if ("SUBSCRIPTION_CANCELED".equalsIgnoreCase(req.eventType()) && req.tenantId() != null) {
            jdbc.update("""
                UPDATE tenant_subscriptions
                SET cancel_at_period_end = true,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ?
            """, req.tenantId());
        } else if ("SUBSCRIPTION_RENEWED".equalsIgnoreCase(req.eventType()) && req.tenantId() != null) {
            jdbc.update("""
                UPDATE tenant_subscriptions
                SET current_period_start = CURRENT_TIMESTAMP,
                    current_period_end = CURRENT_TIMESTAMP + INTERVAL '30 days',
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ?
            """, req.tenantId());
        }

        jdbc.update("""
            INSERT INTO billing_events (id, idempotency_key, tenant_id, event_type, provider, payload, status, processed_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PROCESSED', CURRENT_TIMESTAMP)
        """, eventId, req.idempotencyKey(), req.tenantId(), req.eventType(), provider,
             req.payload() != null ? req.payload() : "{}");

        return new BillingEventDto(eventId, req.idempotencyKey(), req.tenantId(), req.eventType(), provider, "PROCESSED", java.time.Instant.now());
    }
}
