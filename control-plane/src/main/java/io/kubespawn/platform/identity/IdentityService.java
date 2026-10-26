package io.kubespawn.platform.identity;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;

    public IdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Membership(UUID tenantId, String tenantKey, String tenantName, String role, String status) {}
    public record Profile(UUID id, String email, String displayName, String avatarUrl, String status,
            boolean platformAdmin, String authMode, List<Membership> memberships) {}
    public record UserSummary(UUID id, String email, String displayName, String status, boolean platformAdmin,
            Instant createdAt, Instant lastSeenAt, List<Membership> memberships) {}
    public record AdminOverview(long users, long activeUsers, long tenants, long activeEnvironments,
            long queuedEnvironments, long failedEnvironments) {}
    private record IdentityKey(String issuer, String subject) {}
    private record UserRow(UUID id, String email, String displayName, String avatarUrl, String status,
            boolean platformAdmin, Instant createdAt, Instant lastSeenAt) {}

    @Transactional
    public Profile current(Authentication authentication) {
        IdentityKey key = identity(authentication);
        UserRow user = find(key);
        jdbc.update("UPDATE app_users SET last_seen_at=clock_timestamp() WHERE id=?", user.id());
        jdbc.update("UPDATE user_identities SET last_login_at=clock_timestamp() WHERE issuer=? AND subject=?",
            key.issuer(), key.subject());
        return profile(user, key.issuer());
    }

    @Transactional(readOnly = true)
    public Profile current(Principal principal) {
        if (!(principal instanceof Authentication authentication)) {
            throw denied("IDENTITY_REQUIRED", "An authenticated user identity is required.");
        }
        IdentityKey key = identity(authentication);
        return profile(find(key), key.issuer());
    }

    @Transactional
    public Profile update(Authentication authentication, String displayName, String avatarUrl) {
        IdentityKey key = identity(authentication);
        UserRow user = find(key);
        jdbc.update("UPDATE app_users SET display_name=?, avatar_url=?, updated_at=clock_timestamp() WHERE id=?",
            displayName.strip(), blankToNull(avatarUrl), user.id());
        audit(authentication.getName(), "PROFILE_UPDATED", UUID.randomUUID().toString(), user.id().toString());
        return profile(find(key), key.issuer());
    }

    @Transactional(readOnly = true)
    public void requirePlatformAdmin(Principal principal) {
        Profile profile = current(principal);
        if (!profile.platformAdmin()) {
            throw denied("ADMIN_REQUIRED", "Platform administrator access is required.");
        }
    }

    @Transactional(readOnly = true)
    public List<UserSummary> users(Principal principal) {
        requirePlatformAdmin(principal);
        return jdbc.query("""
            SELECT id,email,display_name,avatar_url,status,platform_role,created_at,last_seen_at
            FROM app_users ORDER BY created_at,id LIMIT 200
            """, (rs, row) -> {
                UUID id = rs.getObject("id", UUID.class);
                return new UserSummary(id, rs.getString("email"), rs.getString("display_name"),
                    rs.getString("status"), "ADMIN".equals(rs.getString("platform_role")),
                    rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("last_seen_at")), memberships(id));
            });
    }

    @Transactional(readOnly = true)
    public AdminOverview overview(Principal principal) {
        requirePlatformAdmin(principal);
        return new AdminOverview(count("SELECT count(*) FROM app_users"),
            count("SELECT count(*) FROM app_users WHERE status='ACTIVE'"),
            count("SELECT count(*) FROM tenants WHERE status='ACTIVE'"),
            count("SELECT count(*) FROM environments WHERE desired_state='ACTIVE'"),
            count("SELECT count(*) FROM environments WHERE phase IN ('WAITING_BUILD','QUEUED','PROVISIONING','UPDATING')"),
            count("SELECT count(*) FROM environments WHERE phase IN ('FAILED','DEGRADED','CLEANUP_BLOCKED')"));
    }

    @Transactional
    public UserSummary provision(Principal principal, String issuer, String subject, String email,
            String displayName, String tenantKey, String role, boolean platformAdmin, String requestId) {
        requirePlatformAdmin(principal);
        UUID userId = UUID.randomUUID();
        UUID tenantId = jdbc.query("SELECT id FROM tenants WHERE tenant_key=? AND status='ACTIVE'",
            (rs, row) -> rs.getObject(1, UUID.class), tenantKey).stream().findFirst()
            .orElseThrow(() -> denied("TENANT_NOT_FOUND", "The selected tenant does not exist or is suspended."));
        try {
            jdbc.update("""
                INSERT INTO app_users(id,email,display_name,platform_role) VALUES (?,?,?,?)
                """, userId, blankToNull(email), displayName.strip(), platformAdmin ? "ADMIN" : "USER");
            jdbc.update("INSERT INTO user_identities(user_id,issuer,subject) VALUES (?,?,?)",
                userId, issuer.strip(), subject.strip());
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,?)",
                tenantId, userId, checkedRole(role));
        } catch (DataIntegrityViolationException duplicate) {
            throw new LifecycleException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_EXISTS",
                "That email or external identity is already provisioned.");
        }
        audit(principal.getName(), "USER_PROVISIONED", requestId, userId.toString());
        UserRow user = jdbc.queryForObject("""
            SELECT id,email,display_name,avatar_url,status,platform_role,created_at,last_seen_at
            FROM app_users WHERE id=?
            """, (rs, row) -> row(rs), userId);
        return new UserSummary(user.id(), user.email(), user.displayName(), user.status(), user.platformAdmin(),
            user.createdAt(), user.lastSeenAt(), memberships(user.id()));
    }

    @Transactional
    public UserSummary changeMembership(Principal principal, UUID tenantId, UUID userId, String role,
            String status, String requestId) {
        requirePlatformAdmin(principal);
        String checkedStatus = switch (status) { case "ACTIVE", "SUSPENDED" -> status; default ->
            throw invalid("MEMBERSHIP_STATUS_INVALID", "Membership status must be ACTIVE or SUSPENDED."); };
        int changed = jdbc.update("""
            UPDATE tenant_memberships SET role=?,status=?,updated_at=clock_timestamp()
            WHERE tenant_id=? AND user_id=?
            """, checkedRole(role), checkedStatus, tenantId, userId);
        if (changed == 0) { throw denied("MEMBERSHIP_NOT_FOUND", "Membership not found."); }
        audit(principal.getName(), "MEMBERSHIP_UPDATED", requestId, userId + ":" + tenantId);
        UserRow user = jdbc.query("""
            SELECT id,email,display_name,avatar_url,status,platform_role,created_at,last_seen_at
            FROM app_users WHERE id=?
            """, (rs, row) -> row(rs), userId).stream().findFirst()
            .orElseThrow(() -> denied("USER_NOT_FOUND", "User not found."));
        return new UserSummary(user.id(), user.email(), user.displayName(), user.status(), user.platformAdmin(),
            user.createdAt(), user.lastSeenAt(), memberships(user.id()));
    }

    private UserRow find(IdentityKey key) {
        return jdbc.query("""
            SELECT u.id,u.email,u.display_name,u.avatar_url,u.status,u.platform_role,u.created_at,u.last_seen_at
            FROM app_users u JOIN user_identities i ON i.user_id=u.id
            WHERE i.issuer=? AND i.subject=? AND u.status='ACTIVE'
            """, (rs, row) -> row(rs), key.issuer(), key.subject()).stream().findFirst()
            .orElseThrow(() -> denied("IDENTITY_NOT_PROVISIONED",
                "This identity is not provisioned or has been suspended. Contact a platform administrator."));
    }

    private Profile profile(UserRow user, String issuer) {
        return new Profile(user.id(), user.email(), user.displayName(), user.avatarUrl(), user.status(),
            user.platformAdmin(), "local".equals(issuer) ? "local" : "oidc", memberships(user.id()));
    }

    private List<Membership> memberships(UUID userId) {
        return jdbc.query("""
            SELECT t.id,t.tenant_key,t.name,m.role,m.status
            FROM tenant_memberships m JOIN tenants t ON t.id=m.tenant_id
            WHERE m.user_id=? ORDER BY t.name,t.id
            """, (rs, row) -> new Membership(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), userId);
    }

    private IdentityKey identity(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return new IdentityKey(jwt.getIssuer() == null ? "oidc" : jwt.getIssuer().toString(), jwt.getSubject());
        }
        if (principal instanceof OidcUser oidc) {
            String issuer = oidc.getIssuer() == null ? "oidc" : oidc.getIssuer().toString();
            return new IdentityKey(issuer, oidc.getSubject());
        }
        return new IdentityKey("local", authentication.getName());
    }

    private UserRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            rs.getString("avatar_url"), rs.getString("status"), "ADMIN".equals(rs.getString("platform_role")),
            rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("last_seen_at")));
    }

    private void audit(String actor, String action, String requestId, String target) {
        jdbc.update("""
            INSERT INTO audit_events(id,actor,action,request_id,details)
            VALUES (?,?,?,?,jsonb_build_object('target',?::text))
            """, UUID.randomUUID(), actor, action, requestId, target);
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String checkedRole(String role) {
        return switch (role) { case "VIEWER", "DEVELOPER", "MAINTAINER", "TENANT_ADMIN" -> role; default ->
            throw invalid("MEMBERSHIP_ROLE_INVALID", "Choose VIEWER, DEVELOPER, MAINTAINER, or TENANT_ADMIN."); };
    }
    private static LifecycleException denied(String code, String message) {
        return new LifecycleException(HttpStatus.FORBIDDEN, code, message);
    }
    private static LifecycleException invalid(String code, String message) {
        return new LifecycleException(HttpStatus.BAD_REQUEST, code, message);
    }
}
