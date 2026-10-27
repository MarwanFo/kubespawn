package io.kubespawn.platform.admin;

import io.kubespawn.platform.admin.AdminModels.ProjectQuotaDto;
import io.kubespawn.platform.admin.AdminModels.QuotaConsumption;
import io.kubespawn.platform.admin.AdminModels.TenantQuotaDto;
import io.kubespawn.platform.admin.AdminModels.UpdateQuotaRequest;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final io.kubespawn.platform.notification.NotificationService notificationService;

    public QuotaService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this(jdbc, authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(JdbcTemplate jdbc, AuthorizationService authorization,
                        @org.springframework.beans.factory.annotation.Autowired(required = false) io.kubespawn.platform.notification.NotificationService notificationService) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.notificationService = notificationService;
    }


    @Transactional(readOnly = true)
    public TenantQuotaDto getTenantQuotas(UUID tenantId, Principal actor) {
        authorization.requireRole(actor, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
        return fetchTenantQuota(tenantId);
    }

    @Transactional
    public TenantQuotaDto updateTenantQuotas(UUID tenantId, UpdateQuotaRequest req, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");

        if (req.maxEnvironments() < 1) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "Maximum environments must be at least 1.");
        }
        if (req.cpuMillicoresQuota() < 1000) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "CPU quota must be at least 1000 millicores.");
        }
        if (req.memoryMbQuota() < 1024) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "Memory quota must be at least 1024 MB.");
        }
        if (req.maxTtlHours() < 1) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "Maximum TTL must be at least 1 hour.");
        }

        // Verify requested quotas do not exceed subscribed plan ceiling
        try {
            var planRows = jdbc.query("""
                SELECT p.max_environments, p.cpu_millicores_quota, p.memory_mb_quota,
                       p.storage_mb_quota, p.max_ttl_hours, p.name as plan_name
                FROM tenant_subscriptions s
                JOIN plans p ON p.id = s.plan_id
                WHERE s.tenant_id = ?
            """, (rs, rn) -> java.util.Map.of(
                "max_environments", rs.getInt("max_environments"),
                "cpu_millicores_quota", rs.getInt("cpu_millicores_quota"),
                "memory_mb_quota", rs.getInt("memory_mb_quota"),
                "storage_mb_quota", rs.getInt("storage_mb_quota"),
                "max_ttl_hours", rs.getInt("max_ttl_hours"),
                "plan_name", rs.getString("plan_name")
            ), tenantId);

            if (!planRows.isEmpty()) {
                var plan = planRows.get(0);
                int planMaxEnvs = (int) plan.get("max_environments");
                int planMaxCpu = (int) plan.get("cpu_millicores_quota");
                int planMaxMem = (int) plan.get("memory_mb_quota");
                int planMaxTtl = (int) plan.get("max_ttl_hours");
                String planName = (String) plan.get("plan_name");

                if (req.maxEnvironments() > planMaxEnvs) {
                    throw new LifecycleException(HttpStatus.BAD_REQUEST, "QUOTA_EXCEEDS_PLAN_CEILING",
                        "Requested maximum environments (" + req.maxEnvironments() + ") exceeds plan '" + planName + "' ceiling of " + planMaxEnvs + ". Upgrade plan to increase capacity.");
                }
                if (req.cpuMillicoresQuota() > planMaxCpu) {
                    throw new LifecycleException(HttpStatus.BAD_REQUEST, "QUOTA_EXCEEDS_PLAN_CEILING",
                        "Requested CPU quota (" + req.cpuMillicoresQuota() + "m) exceeds plan '" + planName + "' ceiling of " + planMaxCpu + "m. Upgrade plan to increase capacity.");
                }
                if (req.memoryMbQuota() > planMaxMem) {
                    throw new LifecycleException(HttpStatus.BAD_REQUEST, "QUOTA_EXCEEDS_PLAN_CEILING",
                        "Requested memory quota (" + req.memoryMbQuota() + "MB) exceeds plan '" + planName + "' ceiling of " + planMaxMem + "MB. Upgrade plan to increase capacity.");
                }
                if (req.maxTtlHours() > planMaxTtl) {
                    throw new LifecycleException(HttpStatus.BAD_REQUEST, "QUOTA_EXCEEDS_PLAN_CEILING",
                        "Requested TTL (" + req.maxTtlHours() + "h) exceeds plan '" + planName + "' ceiling of " + planMaxTtl + "h.");
                }
            }
        } catch (org.springframework.jdbc.BadSqlGrammarException ignored) {
            // Table might not exist yet during migration bootstrap
        }

        // Optimistic concurrency check
        Integer currentVersion = jdbc.queryForObject(
            "SELECT policy_version FROM tenant_quotas WHERE tenant_id = ?",
            Integer.class, tenantId
        );

        if (currentVersion == null) {
            // Seed if absent
            jdbc.update("""
                INSERT INTO tenant_quotas (tenant_id, max_environments, max_queued_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version, updated_by)
                VALUES (?, 5, 10, 8000, 16384, 51200, 72, 1, 'system')
                ON CONFLICT (tenant_id) DO NOTHING
            """, tenantId);
            currentVersion = 1;
        }

        if (currentVersion != req.policyVersion()) {
            throw new LifecycleException(HttpStatus.CONFLICT, "CONCURRENT_POLICY_MODIFICATION",
                "Tenant quota policy was modified concurrently. Current version is " + currentVersion + ". Please refresh before updating.");
        }

        int maxQueued = req.maxQueuedEnvironments() != null ? req.maxQueuedEnvironments() : 10;
        int storageMb = req.storageMbQuota() != null ? req.storageMbQuota() : 51200;
        String actorName = actor != null ? actor.getName() : "admin";

        int updated = jdbc.update("""
            UPDATE tenant_quotas
            SET max_environments = ?,
                max_queued_environments = ?,
                cpu_millicores_quota = ?,
                memory_mb_quota = ?,
                storage_mb_quota = ?,
                max_ttl_hours = ?,
                policy_version = policy_version + 1,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = ?
            WHERE tenant_id = ? AND policy_version = ?
        """, req.maxEnvironments(), maxQueued, req.cpuMillicoresQuota(), req.memoryMbQuota(),
             storageMb, req.maxTtlHours(), actorName, tenantId, req.policyVersion());

        if (updated == 0) {
            throw new LifecycleException(HttpStatus.CONFLICT, "CONCURRENT_POLICY_MODIFICATION",
                "Failed to update tenant quotas due to concurrent modification.");
        }

        // Record in audit_events
        jdbc.update("""
            INSERT INTO audit_events (id, tenant_id, actor, action, request_id, details, created_at)
            VALUES (?, ?, ?, 'QUOTA_UPDATED', ?, jsonb_build_object(
                'maxEnvironments', ?::int, 'cpuMillicores', ?::int, 'memoryMb', ?::int, 'maxTtlHours', ?::int, 'newVersion', (? + 1)::int
            ), CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), tenantId, actorName, UUID.randomUUID().toString(),
             req.maxEnvironments(), req.cpuMillicoresQuota(), req.memoryMbQuota(), req.maxTtlHours(), req.policyVersion());

        return fetchTenantQuota(tenantId);
    }

    @Transactional(readOnly = true)
    public ProjectQuotaDto getProjectQuotas(UUID tenantId, UUID projectId, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);
        return fetchProjectQuota(tenantId, projectId);
    }

    @Transactional
    public ProjectQuotaDto updateProjectQuotas(UUID tenantId, UUID projectId, UpdateQuotaRequest req, Principal actor) {
        authorization.requireRole(actor, tenantId, "PORTAL_ADMIN");
        authorization.requireProjectAccess(actor, tenantId, projectId);

        TenantQuotaDto tenantQuota = fetchTenantQuota(tenantId);
        if (req.maxEnvironments() > tenantQuota.maxEnvironments()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA",
                "Project environment ceiling cannot exceed organization ceiling of " + tenantQuota.maxEnvironments() + ".");
        }
        if (req.cpuMillicoresQuota() > tenantQuota.cpuMillicoresQuota()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA",
                "Project CPU quota cannot exceed organization quota of " + tenantQuota.cpuMillicoresQuota() + ".");
        }
        if (req.memoryMbQuota() > tenantQuota.memoryMbQuota()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA",
                "Project memory quota cannot exceed organization quota of " + tenantQuota.memoryMbQuota() + ".");
        }

        Integer currentVersion = jdbc.queryForObject(
            "SELECT policy_version FROM project_quotas WHERE project_id = ?",
            Integer.class, projectId
        );

        if (currentVersion == null) {
            jdbc.update("""
                INSERT INTO project_quotas (project_id, tenant_id, max_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version, updated_by)
                VALUES (?, ?, 3, 4000, 8192, 20480, 72, 1, 'system')
                ON CONFLICT (project_id) DO NOTHING
            """, projectId, tenantId);
            currentVersion = 1;
        }

        if (currentVersion != req.policyVersion()) {
            throw new LifecycleException(HttpStatus.CONFLICT, "CONCURRENT_POLICY_MODIFICATION",
                "Project quota policy was modified concurrently. Current version is " + currentVersion + ". Please refresh before updating.");
        }

        int storageMb = req.storageMbQuota() != null ? req.storageMbQuota() : 20480;
        String actorName = actor != null ? actor.getName() : "admin";

        int updated = jdbc.update("""
            UPDATE project_quotas
            SET max_environments = ?,
                cpu_millicores_quota = ?,
                memory_mb_quota = ?,
                storage_mb_quota = ?,
                max_ttl_hours = ?,
                policy_version = policy_version + 1,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = ?
            WHERE project_id = ? AND policy_version = ?
        """, req.maxEnvironments(), req.cpuMillicoresQuota(), req.memoryMbQuota(),
             storageMb, req.maxTtlHours(), actorName, projectId, req.policyVersion());

        if (updated == 0) {
            throw new LifecycleException(HttpStatus.CONFLICT, "CONCURRENT_POLICY_MODIFICATION",
                "Failed to update project quotas due to concurrent modification.");
        }

        // Record in audit_events
        jdbc.update("""
            INSERT INTO audit_events (id, tenant_id, project_id, actor, action, request_id, details, created_at)
            VALUES (?, ?, ?, ?, 'PROJECT_QUOTA_UPDATED', ?, jsonb_build_object(
                'maxEnvironments', ?::int, 'cpuMillicores', ?::int, 'memoryMb', ?::int, 'maxTtlHours', ?::int, 'newVersion', (? + 1)::int
            ), CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), tenantId, projectId, actorName, UUID.randomUUID().toString(),
             req.maxEnvironments(), req.cpuMillicoresQuota(), req.memoryMbQuota(), req.maxTtlHours(), req.policyVersion());

        return fetchProjectQuota(tenantId, projectId);
    }

    @Transactional
    public void reserveCapacity(UUID tenantId, UUID projectId, int requestedCpu, int requestedMemory, int ttlHours) {
        TenantQuotaDto tenantQuota = fetchTenantQuota(tenantId);

        // 1. Check TTL limit
        if (ttlHours > tenantQuota.maxTtlHours()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "TTL_EXCEEDS_MAXIMUM",
                "Requested TTL (" + ttlHours + "h) exceeds maximum allowed tenant policy (" + tenantQuota.maxTtlHours() + "h).");
        }

        // 2. Check active concurrency and capacity
        QuotaConsumption consumption = tenantQuota.consumption();
        if (consumption.activeEnvironments() >= tenantQuota.maxEnvironments()) {
            throw new LifecycleException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                "Tenant maximum active environments limit (" + tenantQuota.maxEnvironments() + ") reached. Delete unused preview environments or request a quota increase.");
        }
        if (consumption.usedCpuMillicores() + requestedCpu > tenantQuota.cpuMillicoresQuota()) {
            throw new LifecycleException(HttpStatus.TOO_MANY_REQUESTS, "CPU_QUOTA_EXCEEDED",
                "Tenant CPU quota exceeded. Required: " + requestedCpu + "m, Available: " + (tenantQuota.cpuMillicoresQuota() - consumption.usedCpuMillicores()) + "m.");
        }
        if (consumption.usedMemoryMb() + requestedMemory > tenantQuota.memoryMbQuota()) {
            throw new LifecycleException(HttpStatus.TOO_MANY_REQUESTS, "MEMORY_QUOTA_EXCEEDED",
                "Tenant memory quota exceeded. Required: " + requestedMemory + "MB, Available: " + (tenantQuota.memoryMbQuota() - consumption.usedMemoryMb()) + "MB.");
        }

        // 3. Check project quotas if present
        ProjectQuotaDto projectQuota = fetchProjectQuota(tenantId, projectId);
        QuotaConsumption projectConsumption = projectQuota.consumption();
        if (projectConsumption.activeEnvironments() >= projectQuota.maxEnvironments()) {
            throw new LifecycleException(HttpStatus.TOO_MANY_REQUESTS, "PROJECT_QUOTA_EXCEEDED",
                "Project maximum active environments limit (" + projectQuota.maxEnvironments() + ") reached.");
        }

        // 4. Threshold warning: if active environments reach >= 80% of limit, notify tenant admins
        if (notificationService != null && (consumption.activeEnvironments() + 1) >= (tenantQuota.maxEnvironments() * 0.8)) {
            var adminUserIds = jdbc.query(
                "SELECT user_id FROM tenant_memberships WHERE tenant_id = ? AND role IN ('PORTAL_ADMIN', 'TENANT_ADMIN') AND status = 'ACTIVE'",
                (rs, rn) -> (UUID) rs.getObject("user_id"),
                tenantId
            );
            for (UUID adminId : adminUserIds) {
                try {
                    notificationService.createNotification(new io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand(
                        tenantId,
                        adminId,
                        "QUOTA_WARNING",
                        "Quota Warning: Active Environments at 80% Threshold",
                        "Active preview environments will reach " + (consumption.activeEnvironments() + 1) + " of " + tenantQuota.maxEnvironments() + " tenant limit.",
                        "QUOTA",
                        tenantId.toString(),
                        "/admin",
                        "quota-warning:" + tenantId + ":" + (consumption.activeEnvironments() + 1)
                    ));
                } catch (Exception ignored) {}
            }
        }
    }

    private TenantQuotaDto fetchTenantQuota(UUID tenantId) {
        String sql = """
            SELECT max_environments, max_queued_environments, cpu_millicores_quota,
                   memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version,
                   updated_at, updated_by
            FROM tenant_quotas WHERE tenant_id = ?
        """;
        var rows = jdbc.queryForList(sql, tenantId);
        int maxEnvs = 5, maxQueued = 10, cpu = 8000, mem = 16384, storage = 51200, ttl = 72, ver = 1;
        Instant updated = Instant.now();
        String updatedBy = "system";

        if (!rows.isEmpty()) {
            var r = rows.get(0);
            maxEnvs = (int) r.get("max_environments");
            maxQueued = (int) r.get("max_queued_environments");
            cpu = (int) r.get("cpu_millicores_quota");
            mem = (int) r.get("memory_mb_quota");
            storage = (int) r.get("storage_mb_quota");
            ttl = (int) r.get("max_ttl_hours");
            ver = (int) r.get("policy_version");
            updated = ((java.sql.Timestamp) r.get("updated_at")).toInstant();
            updatedBy = (String) r.get("updated_by");
        } else {
            // Self-healing: seed defaults if missing
            jdbc.update("""
                INSERT INTO tenant_quotas (tenant_id, max_environments, max_queued_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version, updated_by)
                VALUES (?, 5, 10, 8000, 16384, 51200, 72, 1, 'system')
                ON CONFLICT (tenant_id) DO NOTHING
            """, tenantId);
        }

        QuotaConsumption consumption = calculateConsumption("WHERE tenant_id = ?", tenantId);
        return new TenantQuotaDto(tenantId, maxEnvs, maxQueued, cpu, mem, storage, ttl, ver, updated, updatedBy, consumption);
    }

    private ProjectQuotaDto fetchProjectQuota(UUID tenantId, UUID projectId) {
        String sql = """
            SELECT max_environments, cpu_millicores_quota, memory_mb_quota,
                   storage_mb_quota, max_ttl_hours, policy_version, updated_at, updated_by
            FROM project_quotas WHERE project_id = ?
        """;
        var rows = jdbc.queryForList(sql, projectId);
        int maxEnvs = 3, cpu = 4000, mem = 8192, storage = 20480, ttl = 72, ver = 1;
        Instant updated = Instant.now();
        String updatedBy = "system";

        if (!rows.isEmpty()) {
            var r = rows.get(0);
            maxEnvs = (int) r.get("max_environments");
            cpu = (int) r.get("cpu_millicores_quota");
            mem = (int) r.get("memory_mb_quota");
            storage = (int) r.get("storage_mb_quota");
            ttl = (int) r.get("max_ttl_hours");
            ver = (int) r.get("policy_version");
            updated = ((java.sql.Timestamp) r.get("updated_at")).toInstant();
            updatedBy = (String) r.get("updated_by");
        } else {
            jdbc.update("""
                INSERT INTO project_quotas (project_id, tenant_id, max_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version, updated_by)
                VALUES (?, ?, 3, 4000, 8192, 20480, 72, 1, 'system')
                ON CONFLICT (project_id) DO NOTHING
            """, projectId, tenantId);
        }

        QuotaConsumption consumption = calculateConsumption("WHERE tenant_id = ? AND project_id = ?", tenantId, projectId);
        return new ProjectQuotaDto(projectId, tenantId, maxEnvs, cpu, mem, storage, ttl, ver, updated, updatedBy, consumption);
    }

    private QuotaConsumption calculateConsumption(String whereClause, Object... params) {
        String sql = "SELECT " +
            "COUNT(*) FILTER (WHERE phase != 'DESTROYED') as active_envs, " +
            "COUNT(*) FILTER (WHERE phase IN ('QUEUED', 'WAITING_BUILD', 'PROVISIONING')) as queued_envs, " +
            "COALESCE(SUM(cpu_millicores) FILTER (WHERE phase != 'DESTROYED'), 0) as used_cpu, " +
            "COALESCE(SUM(memory_mb) FILTER (WHERE phase != 'DESTROYED'), 0) as used_mem, " +
            "COALESCE(SUM(storage_mb) FILTER (WHERE phase != 'DESTROYED'), 0) as used_storage " +
            "FROM environments " + whereClause;

        return jdbc.queryForObject(sql, (rs, rn) -> new QuotaConsumption(
            rs.getInt("active_envs"),
            rs.getInt("queued_envs"),
            rs.getInt("used_cpu"),
            rs.getInt("used_mem"),
            rs.getInt("used_storage")
        ), params);
    }
}
