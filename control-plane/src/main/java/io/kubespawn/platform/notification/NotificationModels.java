package io.kubespawn.platform.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class NotificationModels {

    public record NotificationDto(
        UUID id,
        UUID tenantId,
        String type,
        String title,
        String message,
        String targetType,
        String targetId,
        String targetUrl,
        boolean read,
        Instant readAt,
        Instant createdAt
    ) {}

    public record NotificationPreferencesDto(
        UUID userId,
        UUID tenantId,
        boolean emailEnabled,
        boolean inAppEnabled,
        boolean reviewsEnabled,
        boolean lifecycleEnabled,
        boolean quotaAlertsEnabled,
        Instant updatedAt
    ) {}

    public record UpdateNotificationPreferencesRequest(
        boolean emailEnabled,
        boolean inAppEnabled,
        boolean reviewsEnabled,
        boolean lifecycleEnabled,
        boolean quotaAlertsEnabled
    ) {}

    public record NotificationStatsDto(
        long totalCount,
        long unreadCount
    ) {}

    public record NotificationPageDto(
        List<NotificationDto> items,
        long total,
        int page,
        int size,
        long unreadCount
    ) {}

    public record CreateNotificationCommand(
        UUID tenantId,
        UUID recipientUserId,
        String type,
        String title,
        String message,
        String targetType,
        String targetId,
        String targetUrl,
        String deduplicationKey
    ) {}
}
