package io.kubespawn.platform.identity;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final io.kubespawn.platform.notification.EmailDeliveryAdapter emailDeliveryAdapter;

    public IdentityService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IdentityService(JdbcTemplate jdbc, @org.springframework.beans.factory.annotation.Autowired(required = false) io.kubespawn.platform.notification.EmailDeliveryAdapter emailDeliveryAdapter) {
        this.jdbc = jdbc;
        this.emailDeliveryAdapter = emailDeliveryAdapter;
    }

    public record Membership(UUID tenantId, String tenantKey, String tenantName, String role, String status) {}
    public record Project(UUID id, UUID tenantId, String projectKey, String name, String description,
            String status, Instant createdAt) {}
    public record Invitation(UUID id, UUID tenantId, String email, String role, String status,
            Instant expiresAt, Instant createdAt, String token) {}
    public record TenantMember(UUID userId, String email, String displayName, String avatarUrl,
            String role, String status, Instant joinedAt, Instant lastSeenAt) {}
    public record SessionSummary(String sessionId, Instant creationTime, Instant lastAccessTime, boolean current) {}
    public record Profile(UUID id, String email, String displayName, String avatarUrl, String status,
            String onboardingStatus, boolean platformAdmin, String authMode, List<Membership> memberships) {
        public Profile(UUID id, String email, String displayName, String avatarUrl, String status,
                boolean platformAdmin, String authMode, List<Membership> memberships) {
            this(id, email, displayName, avatarUrl, status, "COMPLETED", platformAdmin, authMode, memberships);
        }
    }
    public record UserSummary(UUID id, String email, String displayName, String status, String onboardingStatus,
            boolean platformAdmin, Instant createdAt, Instant lastSeenAt, List<Membership> memberships) {
        public UserSummary(UUID id, String email, String displayName, String status, boolean platformAdmin,
                Instant createdAt, Instant lastSeenAt, List<Membership> memberships) {
            this(id, email, displayName, status, "COMPLETED", platformAdmin, createdAt, lastSeenAt, memberships);
        }
    }
    public record AdminOverview(long users, long activeUsers, long tenants, long activeEnvironments,
            long queuedEnvironments, long failedEnvironments) {}
    private record IdentityKey(String issuer, String subject) {}
    private record UserRow(UUID id, String email, String displayName, String avatarUrl, String status,
            String onboardingStatus, int securityVersion, boolean platformAdmin, Instant createdAt, Instant lastSeenAt) {}

    @Transactional
    public Profile current(Authentication authentication) {
        IdentityKey key = identity(authentication);
        UserRow user = findOrResolveOnboarding(key, authentication);
        jdbc.update("UPDATE app_users SET last_seen_at=clock_timestamp() WHERE id=?", user.id());
        jdbc.update("UPDATE user_identities SET last_login_at=clock_timestamp() WHERE issuer=? AND subject=?",
            key.issuer(), key.subject());
        return profile(user, key.issuer());
    }

    @Transactional
    public Profile current(Principal principal) {
        if (!(principal instanceof Authentication authentication)) {
            throw denied("IDENTITY_REQUIRED", "An authenticated user identity is required.");
        }
        IdentityKey key = identity(authentication);
        return profile(findOrResolveOnboarding(key, authentication), key.issuer());
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
            SELECT id,email,display_name,avatar_url,status,onboarding_status,security_version,platform_role,created_at,last_seen_at
            FROM app_users ORDER BY created_at,id LIMIT 200
            """, (rs, row) -> {
                UUID id = rs.getObject("id", UUID.class);
                return new UserSummary(id, rs.getString("email"), rs.getString("display_name"),
                    rs.getString("status"), rs.getString("onboarding_status"),
                    "ADMIN".equals(rs.getString("platform_role")),
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

    @Transactional(readOnly = true)
    public List<Project> projects(UUID tenantId) {
        return jdbc.query("""
            SELECT id,tenant_id,project_key,name,description,status,created_at
            FROM projects WHERE tenant_id=? AND status='ACTIVE' ORDER BY name,id
            """, (rs, row) -> new Project(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("project_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
            ), tenantId);
    }

    @Transactional(readOnly = true)
    public List<Project> tenantProjects(Principal principal, UUID tenantId) {
        Profile profile = current(principal);
        boolean isMember = profile.platformAdmin() || profile.memberships().stream()
            .anyMatch(m -> m.tenantId().equals(tenantId) && "ACTIVE".equals(m.status()));
        if (!isMember) {
            throw denied("TENANT_ACCESS_DENIED", "You do not have access to this tenant.");
        }
        return projects(tenantId);
    }

    @Transactional
    public Profile createOrganization(Principal principal, String name, String tenantKey, String requestId) {
        Profile current = current(principal);
        String cleanName = name == null || name.isBlank() ? "New Organization" : name.strip();
        String cleanKey = (tenantKey == null || tenantKey.isBlank()
            ? cleanName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
            : tenantKey.strip().toLowerCase());
        if (cleanKey.isBlank()) {
            cleanKey = "org-" + UUID.randomUUID().toString().substring(0, 8);
        }

        UUID tenantId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO tenants(id, tenant_key, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenantId, cleanKey, cleanName);
        } catch (DataIntegrityViolationException duplicate) {
            throw new LifecycleException(HttpStatus.CONFLICT, "TENANT_KEY_EXISTS",
                "An organization with key '" + cleanKey + "' already exists. Choose a different key.");
        }

        jdbc.update("INSERT INTO tenant_memberships(tenant_id, user_id, role, status) VALUES (?, ?, 'PORTAL_ADMIN', 'ACTIVE')",
            tenantId, current.id());

        UUID projectId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO projects(id, tenant_id, project_key, name, description, status)
            VALUES (?, ?, 'default', 'Default Project', 'Default starter project', 'ACTIVE')
            """, projectId, tenantId);

        jdbc.update("INSERT INTO project_memberships(project_id, user_id, tenant_id) VALUES (?, ?, ?)",
            projectId, current.id(), tenantId);

        jdbc.update("UPDATE app_users SET onboarding_status='COMPLETED', updated_at=clock_timestamp() WHERE id=?",
            current.id());

        audit(principal.getName(), "ORGANIZATION_CREATED", requestId, tenantId.toString());
        return current(principal);
    }

    @Transactional
    public Profile acceptInvitation(Principal principal, String invitationToken, String requestId) {
        if (invitationToken == null || invitationToken.isBlank()) {
            throw invalid("INVITATION_TOKEN_REQUIRED", "An invitation token or code is required.");
        }
        Profile current = current(principal);
        String tokenHash = sha256Hex(invitationToken.strip());

        var inviteList = jdbc.query("""
            SELECT id, tenant_id, email, role, status, expires_at
            FROM tenant_invitations WHERE token_hash=?
            """, (rs, row) -> new Object() {
                final UUID id = rs.getObject("id", UUID.class);
                final UUID tenantId = rs.getObject("tenant_id", UUID.class);
                final String email = rs.getString("email");
                final String role = rs.getString("role");
                final String status = rs.getString("status");
                final Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
            }, tokenHash);

        if (inviteList.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND",
                "The invitation was not found or has already been used.");
        }

        var invite = inviteList.get(0);
        if (!"PENDING".equals(invite.status)) {
            throw new LifecycleException(HttpStatus.GONE, "INVITATION_ALREADY_USED",
                "This invitation has already been " + invite.status.toLowerCase() + ".");
        }

        if (invite.expiresAt.isBefore(Instant.now())) {
            jdbc.update("UPDATE tenant_invitations SET status='EXPIRED' WHERE id=?", invite.id);
            throw new LifecycleException(HttpStatus.GONE, "INVITATION_EXPIRED",
                "This invitation has expired. Request a new invitation from your administrator.");
        }

        if (current.email() != null && !current.email().isBlank()
                && !current.email().equalsIgnoreCase(invite.email)) {
            throw new LifecycleException(HttpStatus.FORBIDDEN, "INVITATION_RECIPIENT_MISMATCH",
                "This invitation was addressed to " + invite.email + ", but your current account is " + current.email() + ".");
        }

        int updated = jdbc.update("""
            UPDATE tenant_invitations
            SET status='ACCEPTED', accepted_at=clock_timestamp(), accepted_by_user_id=?
            WHERE id=? AND status='PENDING'
            """, current.id(), invite.id);

        if (updated == 0) {
            throw new LifecycleException(HttpStatus.CONFLICT, "INVITATION_CONCURRENT_ACCEPT",
                "This invitation was accepted concurrently by another process.");
        }

        jdbc.update("""
            INSERT INTO tenant_memberships(tenant_id, user_id, role, status)
            VALUES (?, ?, ?, 'ACTIVE')
            ON CONFLICT (tenant_id, user_id)
            DO UPDATE SET role=EXCLUDED.role, status='ACTIVE', updated_at=clock_timestamp()
            """, invite.tenantId, current.id(), checkedRole(invite.role));

        jdbc.update("""
            INSERT INTO project_memberships(project_id, user_id, tenant_id)
            SELECT p.id, ?, ? FROM projects p
            WHERE p.tenant_id=? AND p.project_key='default'
            ON CONFLICT DO NOTHING
            """, current.id(), invite.tenantId, invite.tenantId);

        jdbc.update("UPDATE app_users SET onboarding_status='COMPLETED', updated_at=clock_timestamp() WHERE id=?",
            current.id());

        audit(principal.getName(), "INVITATION_ACCEPTED", requestId, invite.id + ":" + current.id());
        return current(principal);
    }

    @Transactional
    public Invitation createInvitation(Principal principal, UUID tenantId, String email, String role, String requestId) {
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        if (cleanEmail.isBlank()) {
            throw invalid("EMAIL_REQUIRED", "An email address is required for the invitation.");
        }
        String checkedRole = checkedRole(role);

        Boolean isMember = jdbc.queryForObject("""
            SELECT count(*) > 0 FROM app_users u
            JOIN tenant_memberships m ON m.user_id = u.id
            WHERE m.tenant_id=? AND LOWER(u.email)=? AND m.status='ACTIVE'
            """, Boolean.class, tenantId, cleanEmail);
        if (Boolean.TRUE.equals(isMember)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS",
                "A user with this email is already an active member of this organization.");
        }

        Boolean hasPending = jdbc.queryForObject("""
            SELECT count(*) > 0 FROM tenant_invitations
            WHERE tenant_id=? AND LOWER(email)=? AND status='PENDING' AND expires_at > clock_timestamp()
            """, Boolean.class, tenantId, cleanEmail);
        if (Boolean.TRUE.equals(hasPending)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "INVITATION_ALREADY_EXISTS",
                "A pending invitation already exists for this email address.");
        }

        Profile current = current(principal);
        UUID inviteId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        jdbc.update("""
            INSERT INTO tenant_invitations(id, tenant_id, email, role, token_hash, invited_by_user_id, status, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """, inviteId, tenantId, cleanEmail, checkedRole, tokenHash, current.id(), Timestamp.from(expiresAt));

        audit(principal.getName(), "INVITATION_CREATED", requestId, inviteId + ":" + tenantId);

        if (emailDeliveryAdapter != null) {
            try {
                String tenantName = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
                emailDeliveryAdapter.sendEmail(
                    cleanEmail,
                    cleanEmail,
                    "Invitation to join " + (tenantName != null ? tenantName : "organization") + " on KubeSpawn",
                    "INVITATION",
                    "You have been invited to join " + (tenantName != null ? tenantName : "this organization") + " as " + checkedRole + ". Use the link below to accept and access the platform.",
                    "/onboarding?invitation=" + rawToken
                );
            } catch (Exception e) {
                // Email delivery failure must not abort invitation creation
            }
        }

        return new Invitation(inviteId, tenantId, cleanEmail, checkedRole, "PENDING", expiresAt, Instant.now(), rawToken);
    }

    @Transactional(readOnly = true)
    public List<Invitation> listInvitations(UUID tenantId) {
        return jdbc.query("""
            SELECT id, tenant_id, email, role, status, expires_at, created_at
            FROM tenant_invitations
            WHERE tenant_id=? ORDER BY created_at DESC
            """, (rs, row) -> new Invitation(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("email"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                null
            ), tenantId);
    }

    @Transactional
    public void revokeInvitation(Principal principal, UUID tenantId, UUID invitationId, String requestId) {
        int rows = jdbc.update("""
            UPDATE tenant_invitations SET status='REVOKED'
            WHERE id=? AND tenant_id=? AND status='PENDING'
            """, invitationId, tenantId);
        if (rows == 0) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND",
                "Pending invitation not found.");
        }
        audit(principal.getName(), "INVITATION_REVOKED", requestId, invitationId.toString());
    }

    @Transactional(readOnly = true)
    public List<TenantMember> listMembers(UUID tenantId) {
        return jdbc.query("""
            SELECT u.id, u.email, u.display_name, u.avatar_url, m.role, m.status, m.created_at, u.last_seen_at
            FROM tenant_memberships m
            JOIN app_users u ON u.id = m.user_id
            WHERE m.tenant_id=?
            ORDER BY m.role, u.display_name
            """, (rs, row) -> new TenantMember(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("last_seen_at"))
            ), tenantId);
    }

    @Transactional
    public void updateMemberRole(Principal principal, UUID tenantId, UUID targetUserId, String newRole, String requestId) {
        String checkedRole = checkedRole(newRole);
        if (!"PORTAL_ADMIN".equals(checkedRole)) {
            assertNotLastAdmin(tenantId, targetUserId);
        }
        int rows = jdbc.update("""
            UPDATE tenant_memberships SET role=?, updated_at=clock_timestamp()
            WHERE tenant_id=? AND user_id=?
            """, checkedRole, tenantId, targetUserId);
        if (rows == 0) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this organization.");
        }
        audit(principal.getName(), "MEMBER_ROLE_UPDATED", requestId, targetUserId + ":" + checkedRole);
    }

    @Transactional
    public void updateMemberStatus(Principal principal, UUID tenantId, UUID targetUserId, String newStatus, String requestId) {
        String checkedStatus = switch (newStatus) {
            case "ACTIVE", "SUSPENDED" -> newStatus;
            default -> throw invalid("STATUS_INVALID", "Status must be ACTIVE or SUSPENDED.");
        };
        if ("SUSPENDED".equals(checkedStatus)) {
            assertNotLastAdmin(tenantId, targetUserId);
        }
        int rows = jdbc.update("""
            UPDATE tenant_memberships SET status=?, updated_at=clock_timestamp()
            WHERE tenant_id=? AND user_id=?
            """, checkedStatus, tenantId, targetUserId);
        if (rows == 0) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this organization.");
        }
        audit(principal.getName(), "MEMBER_STATUS_UPDATED", requestId, targetUserId + ":" + checkedStatus);
    }

    @Transactional
    public void removeMember(Principal principal, UUID tenantId, UUID targetUserId, String requestId) {
        assertNotLastAdmin(tenantId, targetUserId);
        jdbc.update("DELETE FROM project_memberships WHERE user_id=? AND tenant_id=?", targetUserId, tenantId);
        int rows = jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?", tenantId, targetUserId);
        if (rows == 0) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this organization.");
        }
        audit(principal.getName(), "MEMBER_REMOVED", requestId, targetUserId + ":" + tenantId);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions(Principal principal, String currentSessionId) {
        String username = principal.getName();
        return jdbc.query("""
            SELECT session_id, creation_time, last_access_time
            FROM spring_session
            WHERE principal_name=?
            ORDER BY last_access_time DESC
            """, (rs, row) -> {
                String id = rs.getString("session_id");
                return new SessionSummary(
                    id,
                    Instant.ofEpochMilli(rs.getLong("creation_time")),
                    Instant.ofEpochMilli(rs.getLong("last_access_time")),
                    id.equals(currentSessionId)
                );
            }, username);
    }

    @Transactional
    public int revokeOtherSessions(Principal principal, String currentSessionId, String requestId) {
        String username = principal.getName();
        int count;
        if (currentSessionId != null && !currentSessionId.isBlank()) {
            count = jdbc.update("""
                DELETE FROM spring_session
                WHERE principal_name=? AND session_id != ?
                """, username, currentSessionId);
        } else {
            count = jdbc.update("DELETE FROM spring_session WHERE principal_name=?", username);
        }
        audit(username, "SESSIONS_REVOKED", requestId, "count:" + count);
        return count;
    }

    private void assertNotLastAdmin(UUID tenantId, UUID targetUserId) {
        Boolean isTargetAdmin = jdbc.queryForObject("""
            SELECT count(*) > 0 FROM tenant_memberships
            WHERE tenant_id=? AND user_id=? AND role='PORTAL_ADMIN' AND status='ACTIVE'
            """, Boolean.class, tenantId, targetUserId);
        if (Boolean.TRUE.equals(isTargetAdmin)) {
            Integer adminCount = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_memberships
                WHERE tenant_id=? AND role='PORTAL_ADMIN' AND status='ACTIVE'
                """, Integer.class, tenantId);
            if (adminCount != null && adminCount <= 1) {
                throw new LifecycleException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED",
                    "Cannot demote, suspend, or remove the last active administrator for this organization.");
            }
        }
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
            SELECT id,email,display_name,avatar_url,status,onboarding_status,security_version,platform_role,created_at,last_seen_at
            FROM app_users WHERE id=?
            """, (rs, row) -> row(rs), userId);
        return new UserSummary(user.id(), user.email(), user.displayName(), user.status(), user.onboardingStatus(),
            user.platformAdmin(), user.createdAt(), user.lastSeenAt(), memberships(user.id()));
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
            SELECT id,email,display_name,avatar_url,status,onboarding_status,security_version,platform_role,created_at,last_seen_at
            FROM app_users WHERE id=?
            """, (rs, row) -> row(rs), userId).stream().findFirst()
            .orElseThrow(() -> denied("USER_NOT_FOUND", "User not found."));
        return new UserSummary(user.id(), user.email(), user.displayName(), user.status(), user.onboardingStatus(),
            user.platformAdmin(), user.createdAt(), user.lastSeenAt(), memberships(user.id()));
    }

    private UserRow find(IdentityKey key) {
        return jdbc.query("""
            SELECT u.id,u.email,u.display_name,u.avatar_url,u.status,u.onboarding_status,u.security_version,u.platform_role,u.created_at,u.last_seen_at
            FROM app_users u JOIN user_identities i ON i.user_id=u.id
            WHERE i.issuer=? AND i.subject=? AND u.status='ACTIVE'
            """, (rs, row) -> row(rs), key.issuer(), key.subject()).stream().findFirst()
            .orElseThrow(() -> denied("IDENTITY_NOT_PROVISIONED",
                "This identity is not provisioned or has been suspended. Contact a platform administrator."));
    }

    private UserRow findOrResolveOnboarding(IdentityKey key, Authentication authentication) {
        var existing = jdbc.query("""
            SELECT u.id,u.email,u.display_name,u.avatar_url,u.status,u.onboarding_status,u.security_version,u.platform_role,u.created_at,u.last_seen_at
            FROM app_users u JOIN user_identities i ON i.user_id=u.id
            WHERE i.issuer=? AND i.subject=? AND u.status='ACTIVE'
            """, (rs, row) -> row(rs), key.issuer(), key.subject()).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidc) {
            Boolean emailVerified = oidc.getEmailVerified();
            if (emailVerified == null) {
                Object claim = oidc.getClaims().get("email_verified");
                emailVerified = Boolean.TRUE.equals(claim);
            }
            if (Boolean.TRUE.equals(emailVerified)) {
                UUID userId = UUID.randomUUID();
                String email = oidc.getEmail();
                String displayName = oidc.getFullName() != null && !oidc.getFullName().isBlank()
                    ? oidc.getFullName()
                    : (oidc.getPreferredUsername() != null ? oidc.getPreferredUsername() : "New User");
                jdbc.update("""
                    INSERT INTO app_users(id, email, display_name, platform_role, status, onboarding_status)
                    VALUES (?, ?, ?, 'USER', 'ACTIVE', 'PENDING_ONBOARDING')
                    ON CONFLICT (email) DO UPDATE SET updated_at=clock_timestamp()
                    """, userId, email, displayName);
                UUID actualUserId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", UUID.class, email);
                jdbc.update("""
                    INSERT INTO user_identities(user_id, issuer, subject)
                    VALUES (?, ?, ?)
                    ON CONFLICT (issuer, subject) DO NOTHING
                    """, actualUserId, key.issuer(), key.subject());
                return jdbc.queryForObject("""
                    SELECT id,email,display_name,avatar_url,status,onboarding_status,security_version,platform_role,created_at,last_seen_at
                    FROM app_users WHERE id=?
                    """, (rs, row) -> row(rs), actualUserId);
            }
        }

        throw denied("IDENTITY_NOT_PROVISIONED",
            "This identity is not provisioned or has been suspended. Contact a platform administrator.");
    }

    private Profile profile(UserRow user, String issuer) {
        return new Profile(user.id(), user.email(), user.displayName(), user.avatarUrl(), user.status(),
            user.onboardingStatus(), user.platformAdmin(), "local".equals(issuer) ? "local" : "oidc", memberships(user.id()));
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
            rs.getString("avatar_url"), rs.getString("status"), rs.getString("onboarding_status"),
            rs.getInt("security_version"), "ADMIN".equals(rs.getString("platform_role")),
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
        if (role == null) {
            throw invalid("MEMBERSHIP_ROLE_INVALID", "Choose DEVELOPER, QA_REVIEWER, or PORTAL_ADMIN.");
        }
        return switch (role.toUpperCase()) {
            case "DEVELOPER" -> "DEVELOPER";
            case "QA_REVIEWER", "VIEWER" -> "QA_REVIEWER";
            case "PORTAL_ADMIN", "TENANT_ADMIN" -> "PORTAL_ADMIN";
            case "MAINTAINER" -> "DEVELOPER";
            default -> throw invalid("MEMBERSHIP_ROLE_INVALID", "Choose DEVELOPER, QA_REVIEWER, or PORTAL_ADMIN.");
        };
    }
    private static LifecycleException denied(String code, String message) {
        return new LifecycleException(HttpStatus.FORBIDDEN, code, message);
    }
    private static LifecycleException invalid(String code, String message) {
        return new LifecycleException(HttpStatus.BAD_REQUEST, code, message);
    }
}
