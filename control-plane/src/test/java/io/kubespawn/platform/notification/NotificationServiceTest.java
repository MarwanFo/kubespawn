package io.kubespawn.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.identity.IdentityService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand;
import io.kubespawn.platform.notification.NotificationModels.NotificationDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPreferencesDto;
import io.kubespawn.platform.notification.NotificationModels.UpdateNotificationPreferencesRequest;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class NotificationServiceTest {

    private JdbcTemplate jdbc;
    private IdentityService identityService;
    private AuthorizationService authorizationService;
    private EmailDeliveryAdapter emailDeliveryAdapter;
    private NotificationService service;

    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000102");
    private final Principal principal = mock(Principal.class);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        identityService = mock(IdentityService.class);
        authorizationService = mock(AuthorizationService.class);
        emailDeliveryAdapter = mock(EmailDeliveryAdapter.class);

        service = new NotificationService(jdbc, identityService, authorizationService, emailDeliveryAdapter);
    }

    @Test
    void createNotificationSkipsDuplicateDeduplicationKey() {
        when(jdbc.queryForObject(eq("SELECT count(*) > 0 FROM notifications WHERE deduplication_key = ?"), eq(Boolean.class), eq("dup-key")))
            .thenReturn(true);

        service.createNotification(new CreateNotificationCommand(
            tenantId, userId, "REVIEW_REQUESTED", "Review Needed", "Message",
            "REVIEW", "rev-1", "/reviews", "dup-key"
        ));

        verify(emailDeliveryAdapter, never()).sendEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createNotificationSkipsSuspendedUser() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("key-1"))).thenReturn(false);
        // User query returns suspended status
        when(jdbc.query(eq("SELECT email, display_name, status FROM app_users WHERE id = ?"), any(RowMapper.class), eq(userId)))
            .thenAnswer(inv -> {
                RowMapper mapper = inv.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("email")).thenReturn("dev@acme.test");
                when(rs.getString("display_name")).thenReturn("Dave Dev");
                when(rs.getString("status")).thenReturn("SUSPENDED");
                return List.of(mapper.mapRow(rs, 0));
            });

        service.createNotification(new CreateNotificationCommand(
            tenantId, userId, "REVIEW_REQUESTED", "Review Needed", "Message",
            "REVIEW", "rev-1", "/reviews", "key-1"
        ));

        verify(emailDeliveryAdapter, never()).sendEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createNotificationSkipsNonMemberOfTenant() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("key-2"))).thenReturn(false);
        when(jdbc.query(eq("SELECT email, display_name, status FROM app_users WHERE id = ?"), any(RowMapper.class), eq(userId)))
            .thenAnswer(inv -> {
                RowMapper mapper = inv.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("email")).thenReturn("dev@acme.test");
                when(rs.getString("display_name")).thenReturn("Dave Dev");
                when(rs.getString("status")).thenReturn("ACTIVE");
                return List.of(mapper.mapRow(rs, 0));
            });

        // Non member
        when(jdbc.queryForObject(eq("SELECT count(*) > 0 FROM tenant_memberships WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'"),
            eq(Boolean.class), eq(tenantId), eq(userId))).thenReturn(false);

        service.createNotification(new CreateNotificationCommand(
            tenantId, userId, "REVIEW_REQUESTED", "Review Needed", "Message",
            "REVIEW", "rev-1", "/reviews", "key-2"
        ));

        verify(emailDeliveryAdapter, never()).sendEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createNotificationInsertsInAppAndSendsEmail() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("key-3"))).thenReturn(false);
        when(jdbc.query(eq("SELECT email, display_name, status FROM app_users WHERE id = ?"), any(RowMapper.class), eq(userId)))
            .thenAnswer(inv -> {
                RowMapper mapper = inv.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("email")).thenReturn("dev@acme.test");
                when(rs.getString("display_name")).thenReturn("Dave Dev");
                when(rs.getString("status")).thenReturn("ACTIVE");
                return List.of(mapper.mapRow(rs, 0));
            });

        when(jdbc.queryForObject(eq("SELECT count(*) > 0 FROM tenant_memberships WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'"),
            eq(Boolean.class), eq(tenantId), eq(userId))).thenReturn(true);

        // Preferences query returns empty -> defaults (all true)
        when(jdbc.query(anyString(), any(RowMapper.class), eq(userId), eq(tenantId)))
            .thenReturn(List.of());

        service.createNotification(new CreateNotificationCommand(
            tenantId, userId, "REVIEW_REQUESTED", "Review Needed", "Please check PR #42",
            "REVIEW", "rev-1", "/reviews", "key-3"
        ));

        verify(emailDeliveryAdapter).sendEmail(
            eq("dev@acme.test"),
            eq("Dave Dev"),
            eq("Review Needed"),
            eq("REVIEW_REQUESTED"),
            eq("Please check PR #42"),
            eq("/reviews")
        );
    }

    @Test
    void createNotificationHandlesEmailDeliveryFailureSafely() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("key-4"))).thenReturn(false);
        when(jdbc.query(eq("SELECT email, display_name, status FROM app_users WHERE id = ?"), any(RowMapper.class), eq(userId)))
            .thenAnswer(inv -> {
                RowMapper mapper = inv.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("email")).thenReturn("dev@acme.test");
                when(rs.getString("display_name")).thenReturn("Dave Dev");
                when(rs.getString("status")).thenReturn("ACTIVE");
                return List.of(mapper.mapRow(rs, 0));
            });

        when(jdbc.queryForObject(eq("SELECT count(*) > 0 FROM tenant_memberships WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'"),
            eq(Boolean.class), eq(tenantId), eq(userId))).thenReturn(true);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(userId), eq(tenantId)))
            .thenReturn(List.of());

        doThrow(new EmailDeliveryAdapter.EmailDeliveryException("Connection refused", null))
            .when(emailDeliveryAdapter).sendEmail(any(), any(), any(), any(), any(), any());

        // Should complete without throwing exception
        service.createNotification(new CreateNotificationCommand(
            tenantId, userId, "REVIEW_REQUESTED", "Review Needed", "Please check PR #42",
            "REVIEW", "rev-1", "/reviews", "key-4"
        ));
    }

    @Test
    void markAsReadThrowsNotFoundWhenNotificationMissing() {
        var profile = new IdentityService.Profile(userId, "dev@acme.test", "Dave", null, "ACTIVE", false, "local", List.of());
        when(identityService.current(principal)).thenReturn(profile);

        UUID notifId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(notifId), eq(userId), eq(tenantId))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(notifId), eq(userId), eq(tenantId))).thenReturn(false);

        assertThatThrownBy(() -> service.markAsRead(principal, tenantId, notifId))
            .isInstanceOf(LifecycleException.class)
            .hasMessageContaining("Notification not found");
    }

    @Test
    void markAllAsReadUpdatesUnread() {
        var profile = new IdentityService.Profile(userId, "dev@acme.test", "Dave", null, "ACTIVE", false, "local", List.of());
        when(identityService.current(principal)).thenReturn(profile);

        service.markAllAsRead(principal, tenantId);

        verify(jdbc).update(anyString(), eq(userId), eq(tenantId));
    }

    @Test
    void updatePreferencesPersistsFlags() {
        var profile = new IdentityService.Profile(userId, "dev@acme.test", "Dave", null, "ACTIVE", false, "local", List.of());
        when(identityService.current(principal)).thenReturn(profile);

        var req = new UpdateNotificationPreferencesRequest(false, true, true, false, false);
        service.updatePreferences(principal, tenantId, req);

        verify(jdbc).update(anyString(), eq(userId), eq(tenantId), eq(false), eq(true), eq(true), eq(false), eq(false));
    }
}
