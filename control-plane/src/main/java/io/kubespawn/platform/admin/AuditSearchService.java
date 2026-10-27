package io.kubespawn.platform.admin;

import io.kubespawn.platform.admin.AdminModels.AuditEventDto;
import io.kubespawn.platform.admin.AdminModels.AuditPageDto;
import io.kubespawn.platform.identity.AuthorizationService;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditSearchService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public AuditSearchService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public AuditPageDto searchAudit(UUID tenantId, UUID projectId, String action, String actor,
                                    Instant from, Instant to, int limit, int offset, Principal principal) {
        authorization.requireRole(principal, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
        if (projectId != null) {
            authorization.requireProjectAccess(principal, tenantId, projectId);
        }

        StringBuilder where = new StringBuilder(" WHERE (tenant_id = ? OR (tenant_id IS NULL AND environment_id IN (SELECT id FROM environments WHERE tenant_id = ?)))");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(tenantId);

        if (projectId != null) {
            where.append(" AND project_id = ?");
            params.add(projectId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND action ILIKE ?");
            params.add("%" + action.trim() + "%");
        }
        if (actor != null && !actor.isBlank()) {
            where.append(" AND actor ILIKE ?");
            params.add("%" + actor.trim() + "%");
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            params.add(Timestamp.from(to));
        }

        String countSql = "SELECT COUNT(*) FROM audit_events" + where;
        Integer total = jdbc.queryForObject(countSql, Integer.class, params.toArray());
        if (total == null) {
            total = 0;
        }

        String selectSql = "SELECT id, tenant_id, project_id, environment_id, actor, action, request_id, details::text, created_at " +
                           "FROM audit_events" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        List<Object> selectParams = new ArrayList<>(params);
        selectParams.add(limit);
        selectParams.add(offset);

        List<AuditEventDto> items = jdbc.query(selectSql, (rs, rn) -> new AuditEventDto(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("request_id"),
            rs.getString("details"),
            rs.getTimestamp("created_at").toInstant()
        ), selectParams.toArray());

        return new AuditPageDto(items, total, limit, offset);
    }
}
