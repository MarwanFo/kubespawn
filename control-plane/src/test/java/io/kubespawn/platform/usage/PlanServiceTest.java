package io.kubespawn.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.usage.UsageModels.*;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PlanServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AuthorizationService authorization;

    @Mock
    private UsageService usageService;

    private PlanService planService;

    private final UUID tenantId = UUID.randomUUID();
    private final Principal actor = () -> "alice-admin";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        planService = new PlanService(jdbc, authorization, usageService);
    }

    @Test
    void changePlan_UpdatesSubscriptionAndSynchronizesTenantQuotas() {
        PlanDto proPlan = new PlanDto(
            "PRO", "Pro Team", "PRO", "Collaborative development",
            10, 20, 16000, 32768, 102400, 72, 4900, 4.0, 0.8,
            List.of("10 Environments", "16 Cores"), true, 1
        );
        TenantSubscriptionDto initialSub = new TenantSubscriptionDto(
            tenantId, "FREE", "Starter / Free", "FREE", 1, "ACTIVE",
            Instant.now(), Instant.now().plusSeconds(86400), "INTERNAL", false
        );

        when(usageService.fetchPlan("PRO")).thenReturn(proPlan);
        when(usageService.fetchOrCreateSubscription(tenantId)).thenReturn(initialSub);

        planService.changePlan(tenantId, new ChangePlanRequest("PRO"), actor);

        // Verifies subscription update
        verify(jdbc).update(contains("UPDATE tenant_subscriptions"), eq("PRO"), eq(1), eq(tenantId));

        // Verifies tenant_quotas ceiling synchronization
        verify(jdbc).update(contains("INSERT INTO tenant_quotas"),
            eq(tenantId), eq(10), eq(20), eq(16000), eq(32768), eq(102400), eq(72), eq("alice-admin"));

        // Verifies audit event
        verify(jdbc).update(contains("INSERT INTO audit_events"),
            any(UUID.class), eq(tenantId), eq("alice-admin"), anyString(),
            eq("FREE"), eq("PRO"), eq(10), eq(16000), eq(32768), eq(72));
    }

    @Test
    void processBillingEvent_WhenReplayed_ReturnsExistingRecord() {
        UUID existingId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq("evt-idemp-123")))
            .thenReturn(List.of(new BillingEventDto(
                existingId, "evt-idemp-123", tenantId, "INVOICE_PAID", "INTERNAL", "PROCESSED", Instant.now()
            )));

        BillingEventDto result = planService.processBillingEvent(new BillingEventRequest(
            "evt-idemp-123", tenantId, "INVOICE_PAID", "INTERNAL", "{}"
        ));

        assertThat(result.id()).isEqualTo(existingId);
        verify(jdbc, never()).update(contains("INSERT INTO billing_events"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processBillingEvent_WhenSubscriptionCanceled_SetsCancelAtPeriodEnd() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("evt-cancel-456")))
            .thenReturn(Collections.emptyList());

        BillingEventDto result = planService.processBillingEvent(new BillingEventRequest(
            "evt-cancel-456", tenantId, "SUBSCRIPTION_CANCELED", "INTERNAL", "{}"
        ));

        assertThat(result.status()).isEqualTo("PROCESSED");
        verify(jdbc).update(contains("cancel_at_period_end = true"), eq(tenantId));
        verify(jdbc).update(contains("INSERT INTO billing_events"), any(UUID.class), eq("evt-cancel-456"), eq(tenantId), eq("SUBSCRIPTION_CANCELED"), eq("INTERNAL"), eq("{}"));
    }
}
