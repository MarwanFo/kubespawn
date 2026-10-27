package io.kubespawn.platform.notification;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.notification.NotificationModels.NotificationPageDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationPreferencesDto;
import io.kubespawn.platform.notification.NotificationModels.NotificationStatsDto;
import io.kubespawn.platform.notification.NotificationModels.UpdateNotificationPreferencesRequest;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final AuthorizationService authorizationService;

    public NotificationController(
        NotificationService notificationService,
        AuthorizationService authorizationService
    ) {
        this.notificationService = notificationService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<NotificationPageDto> getNotifications(
        @PathVariable UUID tenantId,
        @RequestParam(required = false) Boolean unreadOnly,
        @RequestParam(required = false) String type,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Principal principal
    ) {
        var result = notificationService.getUserNotifications(principal, tenantId, unreadOnly, type, page, size);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<NotificationStatsDto> getStats(
        @PathVariable UUID tenantId,
        Principal principal
    ) {
        var stats = notificationService.getStats(principal, tenantId);
        return ResponseEntity.ok(stats);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
        @PathVariable UUID tenantId,
        @PathVariable UUID id,
        Principal principal
    ) {
        notificationService.markAsRead(principal, tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsReadPost(
        @PathVariable UUID tenantId,
        @PathVariable UUID id,
        Principal principal
    ) {
        notificationService.markAsRead(principal, tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
        @PathVariable UUID tenantId,
        Principal principal
    ) {
        notificationService.markAllAsRead(principal, tenantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferencesDto> getPreferences(
        @PathVariable UUID tenantId,
        Principal principal
    ) {
        var prefs = notificationService.getPreferences(principal, tenantId);
        return ResponseEntity.ok(prefs);
    }

    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferencesDto> updatePreferences(
        @PathVariable UUID tenantId,
        @RequestBody UpdateNotificationPreferencesRequest request,
        Principal principal
    ) {
        var updated = notificationService.updatePreferences(principal, tenantId, request);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/retry-deliveries")
    public ResponseEntity<Map<String, Object>> retryDeliveries(
        @PathVariable UUID tenantId,
        Principal principal
    ) {
        authorizationService.requireRole(principal, tenantId, "PORTAL_ADMIN");
        int retried = notificationService.retryFailedDeliveries();
        return ResponseEntity.ok(Map.of("retriedCount", retried));
    }
}
