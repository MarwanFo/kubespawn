package io.kubespawn.platform.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.api.ApiErrors;
import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.notification.NotificationModels.NotificationDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPageDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPreferencesDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationStatsDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = NotificationController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import({SecurityConfig.class, ApiErrors.class})
@ActiveProfiles("local")
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationService notificationService;
    @MockitoBean AuthorizationService authorizationService;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID notificationId = UUID.fromString("10000000-0000-0000-0000-000000000801");

    @Test
    void userCanListNotifications() throws Exception {
        var item = new NotificationDto(
            notificationId, tenantId, "REVIEW_REQUESTED", "Review Needed",
            "Please review PR #42", "REVIEW", "rev-1", "/reviews",
            false, null, Instant.now()
        );
        when(notificationService.getUserNotifications(any(), eq(tenantId), any(), any(), anyInt(), anyInt()))
            .thenReturn(new NotificationPageDto(List.of(item), 1, 0, 20, 1));

        mvc.perform(get("/api/v1/tenants/{tenantId}/notifications", tenantId)
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.unreadCount").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Review Needed"))
            .andExpect(jsonPath("$.items[0].type").value("REVIEW_REQUESTED"));
    }

    @Test
    void userCanGetNotificationStats() throws Exception {
        when(notificationService.getStats(any(), eq(tenantId)))
            .thenReturn(new NotificationStatsDto(5, 2));

        mvc.perform(get("/api/v1/tenants/{tenantId}/notifications/stats", tenantId)
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(5))
            .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void userCanMarkNotificationAsRead() throws Exception {
        mvc.perform(put("/api/v1/tenants/{tenantId}/notifications/{id}/read", tenantId, notificationId)
                .header("Authorization", TOKEN))
            .andExpect(status().isNoContent());

        verify(notificationService).markAsRead(any(), eq(tenantId), eq(notificationId));
    }

    @Test
    void markAsReadFailsWhenNotFound() throws Exception {
        doThrow(new LifecycleException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found."))
            .when(notificationService).markAsRead(any(), eq(tenantId), eq(notificationId));

        mvc.perform(put("/api/v1/tenants/{tenantId}/notifications/{id}/read", tenantId, notificationId)
                .header("Authorization", TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void userCanMarkAllAsRead() throws Exception {
        mvc.perform(post("/api/v1/tenants/{tenantId}/notifications/read-all", tenantId)
                .header("Authorization", TOKEN))
            .andExpect(status().isNoContent());

        verify(notificationService).markAllAsRead(any(), eq(tenantId));
    }

    @Test
    void userCanGetPreferences() throws Exception {
        var prefs = new NotificationPreferencesDto(
            UUID.randomUUID(), tenantId, true, true, true, true, false, Instant.now()
        );
        when(notificationService.getPreferences(any(java.security.Principal.class), eq(tenantId))).thenReturn(prefs);

        mvc.perform(get("/api/v1/tenants/{tenantId}/notifications/preferences", tenantId)
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailEnabled").value(true))
            .andExpect(jsonPath("$.inAppEnabled").value(true))
            .andExpect(jsonPath("$.quotaAlertsEnabled").value(false));
    }

    @Test
    void userCanUpdatePreferences() throws Exception {
        var updated = new NotificationPreferencesDto(
            UUID.randomUUID(), tenantId, false, true, true, true, false, Instant.now()
        );
        when(notificationService.updatePreferences(any(), eq(tenantId), any())).thenReturn(updated);

        mvc.perform(put("/api/v1/tenants/{tenantId}/notifications/preferences", tenantId)
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "emailEnabled": false,
                      "inAppEnabled": true,
                      "reviewsEnabled": true,
                      "lifecycleEnabled": true,
                      "quotaAlertsEnabled": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailEnabled").value(false))
            .andExpect(jsonPath("$.inAppEnabled").value(true));
    }

    @Test
    void tenantIsolationDeniedForUnauthorizedUser() throws Exception {
        when(notificationService.getUserNotifications(any(), eq(tenantId), any(), any(), anyInt(), anyInt()))
            .thenThrow(new LifecycleException(HttpStatus.FORBIDDEN, "TENANT_ACCESS_DENIED", "You do not have access to this organization."));

        mvc.perform(get("/api/v1/tenants/{tenantId}/notifications", tenantId)
                .header("Authorization", TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("TENANT_ACCESS_DENIED"));
    }
}
