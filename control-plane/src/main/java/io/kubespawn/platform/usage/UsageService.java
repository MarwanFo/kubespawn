package io.kubespawn.platform.usage;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.usage.UsageModels.*;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class UsageService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final ObjectMapper objectMapper;

    @Autowired
    public UsageService(JdbcTemplate jdbc, AuthorizationService authorization, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UsageSummaryDto getUsageSummary(UUID tenantId, Principal actor) {
        authorization.requireRole(actor, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");

        TenantSubscriptionDto subscription = fetchOrCreateSubscription(tenantId);
        PlanDto plan = fetchPlan(subscription.planId());

        // 1. Current active environment consumption
        var consumptionRow = jdbc.queryForMap("""
            SELECT
                COUNT(*) FILTER (WHERE phase != 'DESTROYED') as active_envs,
                COALESCE(SUM(cpu_millicores) FILTER (WHERE phase != 'DESTROYED'), 0) as used_cpu,
                COALESCE(SUM(memory_mb) FILTER (WHERE phase != 'DESTROYED'), 0) as used_mem,
                COALESCE(SUM(storage_mb) FILTER (WHERE phase != 'DESTROYED'), 0) as used_storage
            FROM environments
            WHERE tenant_id = ?
        """, tenantId);

        int activeEnvs = ((Number) consumptionRow.get("active_envs")).intValue();
        int usedCpu = ((Number) consumptionRow.get("used_cpu")).intValue();
        int usedMem = ((Number) consumptionRow.get("used_mem")).intValue();
        int usedStorage = ((Number) consumptionRow.get("used_storage")).intValue();

        // 2. Resource-time consumption in current billing period
        var usageRow = jdbc.queryForMap("""
            SELECT
                COALESCE(SUM(
                    CASE WHEN status = 'CLOSED' THEN core_hours
                         ELSE ROUND((cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4)
                    END
                ), 0.0) as total_core_hours,
                COALESCE(SUM(
                    CASE WHEN status = 'CLOSED' THEN memory_gb_hours
                         ELSE ROUND((memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - started_at))) / 3600.0), 4)
                    END
                ), 0.0) as total_memory_gb_hours
            FROM usage_intervals
            WHERE tenant_id = ?
              AND (started_at >= ? OR status = 'ACTIVE')
        """, tenantId, java.sql.Timestamp.from(subscription.currentPeriodStart()));

        double totalCoreHours = ((Number) usageRow.get("total_core_hours")).doubleValue();
        double totalMemoryGbHours = ((Number) usageRow.get("total_memory_gb_hours")).doubleValue();

        // Estimated cost cents (Resource usage estimate distinct from formal invoice)
        int estimatedResourceCostCents = (int) Math.round(
            (totalCoreHours * plan.cpuRateCentsPerCoreHour()) +
            (totalMemoryGbHours * plan.memoryRateCentsPerGbHour())
        );

        // 3. Project breakdown
        List<ProjectUsageSummaryDto> projectBreakdown = jdbc.query("""
            SELECT
                p.id as project_id,
                p.name as project_name,
                COUNT(e.id) FILTER (WHERE e.phase != 'DESTROYED') as active_envs,
                COALESCE(SUM(
                    CASE WHEN u.status = 'CLOSED' THEN u.core_hours
                         ELSE ROUND((u.cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - u.started_at))) / 3600.0), 4)
                    END
                ), 0.0) as core_hours,
                COALESCE(SUM(
                    CASE WHEN u.status = 'CLOSED' THEN u.memory_gb_hours
                         ELSE ROUND((u.memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - u.started_at))) / 3600.0), 4)
                    END
                ), 0.0) as memory_gb_hours
            FROM projects p
            LEFT JOIN environments e ON e.project_id = p.id
            LEFT JOIN usage_intervals u ON u.project_id = p.id AND (u.started_at >= ? OR u.status = 'ACTIVE')
            WHERE p.tenant_id = ?
            GROUP BY p.id, p.name
            ORDER BY p.name ASC
        """, (rs, rowNum) -> {
            double ch = rs.getDouble("core_hours");
            double mh = rs.getDouble("memory_gb_hours");
            int cost = (int) Math.round((ch * plan.cpuRateCentsPerCoreHour()) + (mh * plan.memoryRateCentsPerGbHour()));
            return new ProjectUsageSummaryDto(
                (UUID) rs.getObject("project_id"),
                rs.getString("project_name"),
                rs.getInt("active_envs"),
                Math.round(ch * 100.0) / 100.0,
                Math.round(mh * 100.0) / 100.0,
                cost
            );
        }, java.sql.Timestamp.from(subscription.currentPeriodStart()), tenantId);

        return new UsageSummaryDto(
            tenantId,
            subscription,
            plan,
            activeEnvs,
            plan.maxEnvironments(),
            usedCpu,
            plan.cpuMillicoresQuota(),
            usedMem,
            plan.memoryMbQuota(),
            usedStorage,
            plan.storageMbQuota(),
            Math.round(totalCoreHours * 100.0) / 100.0,
            Math.round(totalMemoryGbHours * 100.0) / 100.0,
            estimatedResourceCostCents,
            subscription.currentPeriodStart(),
            subscription.currentPeriodEnd(),
            projectBreakdown
        );
    }

    @Transactional
    public ProjectUsageSummaryDto getProjectUsage(UUID tenantId, UUID projectId, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        TenantSubscriptionDto subscription = fetchOrCreateSubscription(tenantId);
        PlanDto plan = fetchPlan(subscription.planId());

        var row = jdbc.queryForMap("""
            SELECT
                p.name as project_name,
                COUNT(e.id) FILTER (WHERE e.phase != 'DESTROYED') as active_envs,
                COALESCE(SUM(
                    CASE WHEN u.status = 'CLOSED' THEN u.core_hours
                         ELSE ROUND((u.cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - u.started_at))) / 3600.0), 4)
                    END
                ), 0.0) as core_hours,
                COALESCE(SUM(
                    CASE WHEN u.status = 'CLOSED' THEN u.memory_gb_hours
                         ELSE ROUND((u.memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - u.started_at))) / 3600.0), 4)
                    END
                ), 0.0) as memory_gb_hours
            FROM projects p
            LEFT JOIN environments e ON e.project_id = p.id
            LEFT JOIN usage_intervals u ON u.project_id = p.id AND (u.started_at >= ? OR u.status = 'ACTIVE')
            WHERE p.tenant_id = ? AND p.id = ?
            GROUP BY p.name
        """, java.sql.Timestamp.from(subscription.currentPeriodStart()), tenantId, projectId);

        double ch = ((Number) row.get("core_hours")).doubleValue();
        double mh = ((Number) row.get("memory_gb_hours")).doubleValue();
        int cost = (int) Math.round((ch * plan.cpuRateCentsPerCoreHour()) + (mh * plan.memoryRateCentsPerGbHour()));

        return new ProjectUsageSummaryDto(
            projectId,
            (String) row.get("project_name"),
            ((Number) row.get("active_envs")).intValue(),
            Math.round(ch * 100.0) / 100.0,
            Math.round(mh * 100.0) / 100.0,
            cost
        );
    }

    @Transactional(readOnly = true)
    public UsageIntervalsPageDto listUsageIntervals(UUID tenantId, UUID projectId, Instant from, Instant to, int page, int size, Principal actor) {
        authorization.requireRole(actor, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
        if (projectId != null) {
            authorization.requireProjectAccess(actor, tenantId, projectId);
        }

        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * limit;

        StringBuilder where = new StringBuilder("WHERE tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (projectId != null) {
            where.append("AND project_id = ? ");
            params.add(projectId);
        }
        if (from != null) {
            where.append("AND started_at >= ? ");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append("AND started_at <= ? ");
            params.add(java.sql.Timestamp.from(to));
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM usage_intervals " + where, Long.class, params.toArray());

        params.add(limit);
        params.add(offset);

        List<UsageIntervalDto> items = jdbc.query("""
            SELECT id, tenant_id, project_id, environment_id, environment_name,
                   cpu_millicores, memory_mb, storage_mb, started_at, ended_at,
                   duration_seconds, core_hours, memory_gb_hours, estimated_cost_cents, status
            FROM usage_intervals
        """ + where + " ORDER BY started_at DESC LIMIT ? OFFSET ?", (rs, rn) -> mapUsageInterval(rs), params.toArray());

        return new UsageIntervalsPageDto(items, page, limit, total != null ? total : 0);
    }

    @Transactional
    public String exportUsageCsv(UUID tenantId, Instant from, Instant to, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");

        StringBuilder where = new StringBuilder("WHERE tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (from != null) {
            where.append("AND started_at >= ? ");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append("AND started_at <= ? ");
            params.add(java.sql.Timestamp.from(to));
        }

        List<UsageIntervalDto> items = jdbc.query("""
            SELECT id, tenant_id, project_id, environment_id, environment_name,
                   cpu_millicores, memory_mb, storage_mb, started_at, ended_at,
                   duration_seconds, core_hours, memory_gb_hours, estimated_cost_cents, status
            FROM usage_intervals
        """ + where + " ORDER BY started_at DESC", (rs, rn) -> mapUsageInterval(rs), params.toArray());

        StringBuilder csv = new StringBuilder();
        csv.append("Interval ID,Project ID,Environment ID,Environment Name,CPU Millicores,Memory MB,Storage MB,Started At,Ended At,Duration Seconds,Core Hours,Memory GB Hours,Estimated Cost Cents,Status\n");
        for (UsageIntervalDto i : items) {
            csv.append(i.id()).append(",")
               .append(i.projectId()).append(",")
               .append(i.environmentId() != null ? i.environmentId() : "").append(",")
               .append("\"").append(i.environmentName().replace("\"", "\"\"")).append("\",")
               .append(i.cpuMillicores()).append(",")
               .append(i.memoryMb()).append(",")
               .append(i.storageMb()).append(",")
               .append(i.startedAt()).append(",")
               .append(i.endedAt() != null ? i.endedAt() : "").append(",")
               .append(i.durationSeconds() != null ? i.durationSeconds() : "").append(",")
               .append(i.coreHours()).append(",")
               .append(i.memoryGbHours()).append(",")
               .append(i.estimatedCostCents()).append(",")
               .append(i.status()).append("\n");
        }
        return csv.toString();
    }

    @Transactional
    public void openInterval(UUID tenantId, UUID projectId, UUID environmentId, String name, int cpu, int mem, int storage) {
        // Double-count prevention: check if active interval already exists
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM usage_intervals WHERE environment_id = ? AND status = 'ACTIVE'",
            Integer.class, environmentId
        );
        if (count != null && count > 0) {
            return; // Interval already opened
        }

        jdbc.update("""
            INSERT INTO usage_intervals (
                id, tenant_id, project_id, environment_id, environment_name,
                cpu_millicores, memory_mb, storage_mb, started_at, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'ACTIVE')
        """, UUID.randomUUID(), tenantId, projectId, environmentId, name, cpu, mem, storage);
    }

    @Transactional
    public void closeInterval(UUID environmentId) {
        if (environmentId == null) return;

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
        """, environmentId);
    }

    @Transactional
    public void reconcileOrphanIntervals() {
        jdbc.update("""
            UPDATE usage_intervals u
            SET ended_at = COALESCE(e.destroyed_at, e.updated_at, clock_timestamp()),
                duration_seconds = GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, clock_timestamp()) - u.started_at))::bigint),
                core_hours = ROUND((u.cpu_millicores::numeric / 1000.0) * (GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, clock_timestamp()) - u.started_at))) / 3600.0), 4),
                memory_gb_hours = ROUND((u.memory_mb::numeric / 1024.0) * (GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(e.destroyed_at, e.updated_at, clock_timestamp()) - u.started_at))) / 3600.0), 4),
                status = 'CLOSED'
            FROM environments e
            WHERE u.environment_id = e.id
              AND e.phase = 'DESTROYED'
              AND u.status = 'ACTIVE'
        """);
    }

    public TenantSubscriptionDto fetchOrCreateSubscription(UUID tenantId) {
        var rows = jdbc.query("""
            SELECT s.tenant_id, s.plan_id, p.name as plan_name, p.tier, s.plan_version,
                   s.status, s.current_period_start, s.current_period_end, s.billing_provider,
                   s.cancel_at_period_end
            FROM tenant_subscriptions s
            JOIN plans p ON p.id = s.plan_id
            WHERE s.tenant_id = ?
        """, (rs, rn) -> new TenantSubscriptionDto(
            (UUID) rs.getObject("tenant_id"),
            rs.getString("plan_id"),
            rs.getString("plan_name"),
            rs.getString("tier"),
            rs.getInt("plan_version"),
            rs.getString("status"),
            rs.getTimestamp("current_period_start").toInstant(),
            rs.getTimestamp("current_period_end").toInstant(),
            rs.getString("billing_provider"),
            rs.getBoolean("cancel_at_period_end")
        ), tenantId);

        if (!rows.isEmpty()) {
            return rows.get(0);
        }

        // Auto-provision FREE subscription if not present
        jdbc.update("""
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, plan_version, status, current_period_start, current_period_end, billing_provider
            ) VALUES (?, 'FREE', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days', 'INTERNAL')
            ON CONFLICT (tenant_id) DO NOTHING
        """, tenantId);

        return fetchOrCreateSubscription(tenantId);
    }

    public PlanDto fetchPlan(String planId) {
        var rows = jdbc.query("SELECT * FROM plans WHERE id = ?", (rs, rn) -> mapPlan(rs), planId);
        if (rows.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Plan '" + planId + "' does not exist.");
        }
        return rows.get(0);
    }

    public List<PlanDto> listPlans() {
        return jdbc.query("SELECT * FROM plans WHERE is_active = true ORDER BY price_cents_monthly ASC", (rs, rn) -> mapPlan(rs));
    }

    private UsageIntervalDto mapUsageInterval(ResultSet rs) throws SQLException {
        var ended = rs.getTimestamp("ended_at");
        var dur = rs.getObject("duration_seconds");
        return new UsageIntervalDto(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("project_id"),
            (UUID) rs.getObject("environment_id"),
            rs.getString("environment_name"),
            rs.getInt("cpu_millicores"),
            rs.getInt("memory_mb"),
            rs.getInt("storage_mb"),
            rs.getTimestamp("started_at").toInstant(),
            ended != null ? ended.toInstant() : null,
            dur != null ? ((Number) dur).longValue() : null,
            rs.getDouble("core_hours"),
            rs.getDouble("memory_gb_hours"),
            rs.getInt("estimated_cost_cents"),
            rs.getString("status")
        );
    }

    private PlanDto mapPlan(ResultSet rs) throws SQLException {
        List<String> features = Collections.emptyList();
        String json = rs.getString("features");
        if (json != null && !json.isBlank()) {
            try {
                features = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                features = List.of(json);
            }
        }
        return new PlanDto(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("tier"),
            rs.getString("description"),
            rs.getInt("max_environments"),
            rs.getInt("max_queued_environments"),
            rs.getInt("cpu_millicores_quota"),
            rs.getInt("memory_mb_quota"),
            rs.getInt("storage_mb_quota"),
            rs.getInt("max_ttl_hours"),
            rs.getInt("price_cents_monthly"),
            rs.getDouble("cpu_rate_cents_per_core_hour"),
            rs.getDouble("memory_rate_cents_per_gb_hour"),
            features,
            rs.getBoolean("is_active"),
            rs.getInt("version")
        );
    }
}
