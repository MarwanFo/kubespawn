package io.kubespawn.platform.notification;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.identity.IdentityService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand;
import io.kubespawn.platform.notification.NotificationModels.NotificationDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPageDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPreferencesDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationStatsDto;
import io.kubespawn.platform.notification.NotificationModels.UpdateNotificationPreferencesRequest;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;
    private final IdentityService identityService;
    private final AuthorizationService authorizationService;
    private final EmailDeliveryAdapter emailDeliveryAdapter;

    public NotificationService(
        JdbcTemplate jdbc,
        IdentityService identityService,
        AuthorizationService authorizationService,
        EmailDeliveryAdapter emailDeliveryAdapter
    ) {
        this.jdbc = jdbc;
        this.identityService = identityService;
        this.authorizationService = authorizationService;
        this.emailDeliveryAdapter = emailDeliveryAdapter;
    }

    @Transactional
    public void createNotification(CreateNotificationCommand cmd) {
        // 1. Deduplication check: if deduplication_key already exists, do nothing
        if (cmd.deduplicationKey() != null && !cmd.deduplicationKey().isBlank()) {
            Boolean exists = jdbc.queryForObject(
                "SELECT count(*) > 0 FROM notifications WHERE deduplication_key = ?",
                Boolean.class, cmd.deduplicationKey()
            );
            if (Boolean.TRUE.equals(exists)) {
                log.info("Notification with deduplication key '{}' already exists. Skipping duplicate.", cmd.deduplicationKey());
                return;
            }
        }

        // 2. Resolve recipient details and verify recipient is active
        var userRows = jdbc.query(
            "SELECT email, display_name, status FROM app_users WHERE id = ?",
            (rs, rn) -> new RecipientInfo(rs.getString("email"), rs.getString("display_name"), rs.getString("status")),
            cmd.recipientUserId()
        );

        if (userRows.isEmpty()) {
            log.warn("Recipient user {} not found. Cannot create notification.", cmd.recipientUserId());
            return;
        }

        RecipientInfo recipient = userRows.get(0);
        if ("SUSPENDED".equalsIgnoreCase(recipient.status())) {
            log.warn("Recipient user {} is SUSPENDED. Skipping notification delivery.", cmd.recipientUserId());
            return;
        }

        // Verify active tenant membership (except for invitations where user may not yet be a member)
        if (!"INVITATION".equalsIgnoreCase(cmd.type())) {
            Boolean isMember = jdbc.queryForObject(
                "SELECT count(*) > 0 FROM tenant_memberships WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Boolean.class, cmd.tenantId(), cmd.recipientUserId()
            );
            if (!Boolean.TRUE.equals(isMember)) {
                log.warn("Recipient user {} is not an active member of tenant {}. Skipping notification.",
                    cmd.recipientUserId(), cmd.tenantId());
                return;
            }
        }

        // 3. Check notification preferences
        NotificationPreferencesDto prefs = getPreferences(cmd.recipientUserId(), cmd.tenantId());
        boolean categoryEnabled = isCategoryEnabled(cmd.type(), prefs);

        UUID notificationId = UUID.randomUUID();

        // 4. In-App delivery
        if (prefs.inAppEnabled() && categoryEnabled) {
            jdbc.update("""
                INSERT INTO notifications (
                    id, tenant_id, recipient_user_id, type, title, message,
                    target_type, target_id, target_url, deduplication_key, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (deduplication_key) DO NOTHING
            """, notificationId, cmd.tenantId(), cmd.recipientUserId(), cmd.type(),
                cmd.title(), cmd.message(), cmd.targetType(), cmd.targetId(), cmd.targetUrl(),
                cmd.deduplicationKey());
        }

        // 5. Email delivery
        if (prefs.emailEnabled() && categoryEnabled && recipient.email() != null && !recipient.email().isBlank()) {
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO notification_deliveries (
                    id, notification_id, recipient_email, channel, status, attempts, created_at, updated_at
                ) VALUES (?, ?, ?, 'EMAIL', 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, deliveryId, notificationId, recipient.email());

            try {
                emailDeliveryAdapter.sendEmail(
                    recipient.email(),
                    recipient.displayName(),
                    cmd.title(),
                    cmd.type(),
                    cmd.message(),
                    cmd.targetUrl()
                );

                jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'SENT', delivered_at = CURRENT_TIMESTAMP, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, deliveryId);
            } catch (Exception e) {
                log.warn("Delivery to {} failed: {}. Will be retried.", recipient.email(), e.getMessage());
                jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'FAILED', last_error = ?, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, e.getMessage(), deliveryId);
            }
        }
    }

    @Transactional(readOnly = true)
    public NotificationPageDto getUserNotifications(
        Principal actor,
        UUID tenantId,
        Boolean unreadOnly,
        String typeFilter,
        int page,
        int size
    ) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        UUID userId = profile.id();

        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, type, title, message, target_type, target_id, target_url,
                   read_at, created_at
            FROM notifications
            WHERE recipient_user_id = ? AND tenant_id = ?
        """);

        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(tenantId);

        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND read_at IS NULL");
        }

        if (typeFilter != null && !typeFilter.isBlank() && !"ALL".equalsIgnoreCase(typeFilter)) {
            sql.append(" AND type = ?");
            params.add(typeFilter.toUpperCase());
        }

        // Count total
        String countSql = "SELECT count(*) FROM (" + sql + ") AS counted";
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) total = 0L;

        // Count unread
        Long unreadCount = jdbc.queryForObject("""
            SELECT count(*) FROM notifications
            WHERE recipient_user_id = ? AND tenant_id = ? AND read_at IS NULL
        """, Long.class, userId, tenantId);
        if (unreadCount == null) unreadCount = 0L;

        // Pagination
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        List<NotificationDto> items = jdbc.query(sql.toString(), (rs, rn) -> mapNotification(rs), params.toArray());

        return new NotificationPageDto(items, total, page, size, unreadCount);
    }

    @Transactional
    public void markAsRead(Principal actor, UUID tenantId, UUID notificationId) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        UUID userId = profile.id();

        int rows = jdbc.update("""
            UPDATE notifications
            SET read_at = CURRENT_TIMESTAMP
            WHERE id = ? AND recipient_user_id = ? AND tenant_id = ? AND read_at IS NULL
        """, notificationId, userId, tenantId);

        if (rows == 0) {
            Boolean exists = jdbc.queryForObject(
                "SELECT count(*) > 0 FROM notifications WHERE id = ? AND recipient_user_id = ? AND tenant_id = ?",
                Boolean.class, notificationId, userId, tenantId
            );
            if (!Boolean.TRUE.equals(exists)) {
                throw new LifecycleException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found.");
            }
        }
    }

    @Transactional
    public void markAllAsRead(Principal actor, UUID tenantId) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        UUID userId = profile.id();

        jdbc.update("""
            UPDATE notifications
            SET read_at = CURRENT_TIMESTAMP
            WHERE recipient_user_id = ? AND tenant_id = ? AND read_at IS NULL
        """, userId, tenantId);
    }

    @Transactional(readOnly = true)
    public NotificationStatsDto getStats(Principal actor, UUID tenantId) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        UUID userId = profile.id();

        Long total = jdbc.queryForObject("""
            SELECT count(*) FROM notifications
            WHERE recipient_user_id = ? AND tenant_id = ?
        """, Long.class, userId, tenantId);

        Long unread = jdbc.queryForObject("""
            SELECT count(*) FROM notifications
            WHERE recipient_user_id = ? AND tenant_id = ? AND read_at IS NULL
        """, Long.class, userId, tenantId);

        return new NotificationStatsDto(total != null ? total : 0L, unread != null ? unread : 0L);
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesDto getPreferences(Principal actor, UUID tenantId) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        return getPreferences(profile.id(), tenantId);
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesDto getPreferences(UUID userId, UUID tenantId) {
        var rows = jdbc.query("""
            SELECT user_id, tenant_id, email_enabled, in_app_enabled, reviews_enabled,
                   lifecycle_enabled, quota_alerts_enabled, updated_at
            FROM notification_preferences
            WHERE user_id = ? AND (tenant_id = ? OR tenant_id IS NULL)
            ORDER BY tenant_id NULLS LAST
            LIMIT 1
        """, (rs, rn) -> mapPreferences(rs), userId, tenantId);

        if (!rows.isEmpty()) {
            return rows.get(0);
        }

        // Return defaults (all enabled)
        return new NotificationPreferencesDto(userId, tenantId, true, true, true, true, true, Instant.now());
    }

    @Transactional
    public NotificationPreferencesDto updatePreferences(Principal actor, UUID tenantId, UpdateNotificationPreferencesRequest req) {
        authorizationService.requireTenantAccess(actor, tenantId);
        IdentityService.Profile profile = identityService.current(actor);
        UUID userId = profile.id();

        jdbc.update("""
            INSERT INTO notification_preferences (
                id, user_id, tenant_id, email_enabled, in_app_enabled, reviews_enabled,
                lifecycle_enabled, quota_alerts_enabled, updated_at
            ) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET
                email_enabled = EXCLUDED.email_enabled,
                in_app_enabled = EXCLUDED.in_app_enabled,
                reviews_enabled = EXCLUDED.reviews_enabled,
                lifecycle_enabled = EXCLUDED.lifecycle_enabled,
                quota_alerts_enabled = EXCLUDED.quota_alerts_enabled,
                updated_at = CURRENT_TIMESTAMP
        """, userId, tenantId, req.emailEnabled(), req.inAppEnabled(),
            req.reviewsEnabled(), req.lifecycleEnabled(), req.quotaAlertsEnabled());

        return getPreferences(userId, tenantId);
    }

    @Transactional
    public int retryFailedDeliveries() {
        var failedDeliveries = jdbc.query("""
            SELECT d.id, d.recipient_email, d.attempts, n.type, n.title, n.message, n.target_url, u.display_name
            FROM notification_deliveries d
            JOIN notifications n ON n.id = d.notification_id
            JOIN app_users u ON u.id = n.recipient_user_id
            WHERE d.status = 'FAILED' AND d.attempts < 3
            ORDER BY d.created_at ASC
            LIMIT 20
        """, (rs, rn) -> new FailedDeliveryRow(
            (UUID) rs.getObject("id"),
            rs.getString("recipient_email"),
            rs.getString("display_name"),
            rs.getInt("attempts"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getString("target_url")
        ));

        int retriedCount = 0;
        for (var delivery : failedDeliveries) {
            try {
                emailDeliveryAdapter.sendEmail(
                    delivery.recipientEmail(),
                    delivery.displayName(),
                    delivery.title(),
                    delivery.type(),
                    delivery.message(),
                    delivery.targetUrl()
                );

                jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'SENT', delivered_at = CURRENT_TIMESTAMP, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, delivery.id());
                retriedCount++;
            } catch (Exception e) {
                log.warn("Retry delivery {} failed: {}", delivery.id(), e.getMessage());
                jdbc.update("""
                    UPDATE notification_deliveries
                    SET attempts = attempts + 1, last_error = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """, e.getMessage(), delivery.id());
            }
        }

        return retriedCount;
    }

    private boolean isCategoryEnabled(String type, NotificationPreferencesDto prefs) {
        if (type == null) return true;
        return switch (type.toUpperCase()) {
            case "REVIEW_REQUESTED", "REVIEW_DECISION" -> prefs.reviewsEnabled();
            case "ENVIRONMENT_EXPIRING" -> prefs.lifecycleEnabled();
            case "QUOTA_WARNING" -> prefs.quotaAlertsEnabled();
            default -> true;
        };
    }

    private NotificationDto mapNotification(ResultSet rs) throws SQLException {
        Timestamp readAtTs = rs.getTimestamp("read_at");
        Timestamp createdAtTs = rs.getTimestamp("created_at");

        return new NotificationDto(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getString("target_type"),
            rs.getString("target_id"),
            rs.getString("target_url"),
            readAtTs != null,
            readAtTs != null ? readAtTs.toInstant() : null,
            createdAtTs != null ? createdAtTs.toInstant() : Instant.now()
        );
    }

    private NotificationPreferencesDto mapPreferences(ResultSet rs) throws SQLException {
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        return new NotificationPreferencesDto(
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getBoolean("email_enabled"),
            rs.getBoolean("in_app_enabled"),
            rs.getBoolean("reviews_enabled"),
            rs.getBoolean("lifecycle_enabled"),
            rs.getBoolean("quota_alerts_enabled"),
            updatedAtTs != null ? updatedAtTs.toInstant() : Instant.now()
        );
    }

    private record RecipientInfo(String email, String displayName, String status) {}
    private record FailedDeliveryRow(
        UUID id, String recipientEmail, String displayName, int attempts,
        String type, String title, String message, String targetUrl
    ) {}
}
