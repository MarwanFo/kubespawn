package io.kubespawn.platform.api;

import io.kubespawn.platform.identity.IdentityService;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final JdbcTemplate jdbc;
    private final IdentityService identities;

    public EnvironmentService(JdbcTemplate jdbc, IdentityService identities) { this.jdbc = jdbc; this.identities = identities; }

    @Transactional(readOnly = true)
    public List<EnvironmentSummary> list(int limit, Principal principal) {
        IdentityService.Profile profile = identities.current(principal);
        StringBuilder sql = new StringBuilder("""
            SELECT e.* FROM environments e JOIN environment_sources s ON s.id=e.source_id
            """);
        List<Object> parameters = new ArrayList<>();
        if (!profile.platformAdmin()) {
            List<String> tenants = profile.memberships().stream()
                .filter(membership -> "ACTIVE".equals(membership.status()))
                .map(IdentityService.Membership::tenantKey).toList();
            if (tenants.isEmpty()) { return List.of(); }
            sql.append(" WHERE s.tenant_key IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(tenants.size(), "?")));
            sql.append(")");
            parameters.addAll(tenants);
        }
        sql.append(" ORDER BY e.created_at DESC,e.id DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> summary(rs), parameters.toArray());
    }

    @Transactional(readOnly = true)
    public List<EnvironmentSummary> listForProject(java.util.UUID tenantId, java.util.UUID projectId, int limit, Principal principal) {
        String sql = """
            SELECT e.* FROM environments e
            WHERE e.tenant_id = ? AND e.project_id = ?
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT ?
            """;
        return jdbc.query(sql, (rs, row) -> summary(rs), tenantId, projectId, limit);
    }

    private EnvironmentSummary summary(ResultSet rs) throws SQLException {
        return new EnvironmentSummary(
            rs.getObject("id", java.util.UUID.class),
            rs.getObject("tenant_id", java.util.UUID.class),
            rs.getObject("project_id", java.util.UUID.class),
            rs.getString("repository_name"),
            rs.getString("namespace"), rs.getString("desired_state"), rs.getString("phase"),
            rs.getString("requested_revision"), rs.getString("serving_revision"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            rs.getString("requested_ref"), rs.getLong("intent_version"));
    }
}
