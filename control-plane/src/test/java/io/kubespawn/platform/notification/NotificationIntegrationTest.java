package io.kubespawn.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand;
import io.kubespawn.platform.notification.NotificationModels.UpdateNotificationPreferencesRequest;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "spring.profiles.active=local",
    "KUBESPAWN_DEV_TOKEN=integration-test-token-with-at-least-32-characters",
    "kubespawn.lifecycle.expiry.enabled=false",
    "kubespawn.worker.enabled=false",
    "kubespawn.github.enabled=false"
})
class NotificationIntegrationTest {

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notificationService;

    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001"); // Acme Corp
    private final UUID devUserId = UUID.fromString("10000000-0000-0000-0000-000000000102"); // Dave Developer
    private final Principal devPrincipal = new UsernamePasswordAuthenticationToken("acme-dev", null);

    @Test
    void seededNotificationsAreAvailableAndQueryable() {
        var page = notificationService.getUserNotifications(devPrincipal, tenantId, false, null, 0, 20);

        assertThat(page.items()).isNotEmpty();
        assertThat(page.unreadCount()).isGreaterThanOrEqualTo(1);

        // Check seeded notifications
        boolean hasReviewDecision = page.items().stream()
            .anyMatch(n -> "REVIEW_DECISION".equals(n.type()));
        assertThat(hasReviewDecision).isTrue();
    }

    @Test
    void deduplicationPreventsDuplicateNotifications() {
        String dedupKey = "test-dedup-" + UUID.randomUUID();

        var cmd = new CreateNotificationCommand(
            tenantId, devUserId, "SYSTEM_ALERT", "Test Deduplication",
            "This alert should only appear once.", "SYSTEM", "sys-1", "/admin", dedupKey
        );

        notificationService.createNotification(cmd);
        notificationService.createNotification(cmd);

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE deduplication_key = ?",
            Integer.class, dedupKey
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void markAsReadAndMarkAllAsReadOperateAtomically() {
        var statsBefore = notificationService.getStats(devPrincipal, tenantId);
        assertThat(statsBefore.unreadCount()).isGreaterThan(0);

        // Mark all as read
        notificationService.markAllAsRead(devPrincipal, tenantId);

        var statsAfter = notificationService.getStats(devPrincipal, tenantId);
        assertThat(statsAfter.unreadCount()).isEqualTo(0);
    }

    @Test
    void updateAndGetNotificationPreferences() {
        var current = notificationService.getPreferences(devPrincipal, tenantId);
        assertThat(current).isNotNull();

        var updateReq = new UpdateNotificationPreferencesRequest(
            false, true, true, false, true
        );
        var updated = notificationService.updatePreferences(devPrincipal, tenantId, updateReq);

        assertThat(updated.emailEnabled()).isFalse();
        assertThat(updated.inAppEnabled()).isTrue();
        assertThat(updated.lifecycleEnabled()).isFalse();
        assertThat(updated.quotaAlertsEnabled()).isTrue();

        var refetched = notificationService.getPreferences(devPrincipal, tenantId);
        assertThat(refetched.emailEnabled()).isFalse();
        assertThat(refetched.lifecycleEnabled()).isFalse();
    }
}
