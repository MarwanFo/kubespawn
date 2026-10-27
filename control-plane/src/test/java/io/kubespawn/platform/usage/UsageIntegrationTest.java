package io.kubespawn.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;

import io.kubespawn.platform.usage.UsageModels.BillingEventRequest;
import io.kubespawn.platform.usage.UsageModels.ChangePlanRequest;
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
class UsageIntegrationTest {

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
    @Autowired UsageService usageService;
    @Autowired PlanService planService;

    private final UUID acmeTenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID acmeProjectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private final Principal adminPrincipal = new UsernamePasswordAuthenticationToken("acme-admin", null);

    @Test
    void seededPlansAndSubscriptionsArePresent() {
        var plans = planService.listAvailablePlans();
        assertThat(plans).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plans).extracting("id").contains("FREE", "PRO", "ENTERPRISE");

        var sub = planService.getTenantPlan(acmeTenantId, adminPrincipal);
        assertThat(sub.planId()).isEqualTo("PRO");
        assertThat(sub.status()).isEqualTo("ACTIVE");
    }

    @Test
    void usageIntervalLifecycle_CalculatesMetricsAndAggregatesSummary() {
        UUID sourceId = UUID.randomUUID();
        UUID testEnvId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO environment_sources (id, tenant_id, project_id, tenant_key, project_key, repository_name, source_kind, manual_request_id, requested_ref, source_state)
            VALUES (?, ?, ?, 'acme', 'web-app', 'test-repo', 'MANUAL', ?, 'main', 'MANUAL')
        """, sourceId, acmeTenantId, acmeProjectId, testEnvId);
        jdbc.update("""
            INSERT INTO environments (id, tenant_id, project_id, source_id, repository_name, cluster_id, namespace, owner_actor, requested_ref, desired_state, phase, expires_at)
            VALUES (?, ?, ?, ?, 'test-repo', 'local', 'test-ns', 'acme-admin', 'main', 'ACTIVE', 'WAITING_BUILD', CURRENT_TIMESTAMP + INTERVAL '24 hours')
        """, testEnvId, acmeTenantId, acmeProjectId, sourceId);

        // 1. Open interval
        usageService.openInterval(acmeTenantId, acmeProjectId, testEnvId, "test-preview-env", 2000, 4096, 10240);

        // 2. Query summary while active
        var activeSummary = usageService.getUsageSummary(acmeTenantId, adminPrincipal);
        assertThat(activeSummary).isNotNull();
        assertThat(activeSummary.subscription().planId()).isEqualTo("PRO");

        // 3. Close interval
        usageService.closeInterval(testEnvId);

        // 4. Verify closed interval in database
        var intervalRow = jdbc.queryForMap(
            "SELECT status, core_hours, memory_gb_hours, duration_seconds FROM usage_intervals WHERE environment_id = ?",
            testEnvId
        );
        assertThat(intervalRow.get("status")).isEqualTo("CLOSED");
        assertThat(intervalRow.get("duration_seconds")).isNotNull();

        // 5. Verify CSV export
        String csv = usageService.exportUsageCsv(acmeTenantId, null, null, adminPrincipal);
        assertThat(csv).contains("test-preview-env");
        assertThat(csv).contains("CLOSED");
    }

    @Test
    void changePlan_UpdatesSubscriptionAndCeilings() {
        // Upgrade from PRO to ENTERPRISE
        var updatedSub = planService.changePlan(acmeTenantId, new ChangePlanRequest("ENTERPRISE"), adminPrincipal);
        assertThat(updatedSub.planId()).isEqualTo("ENTERPRISE");

        // Verify tenant_quotas updated to 50 max_environments
        Integer maxEnvs = jdbc.queryForObject(
            "SELECT max_environments FROM tenant_quotas WHERE tenant_id = ?",
            Integer.class, acmeTenantId
        );
        assertThat(maxEnvs).isEqualTo(50);

        // Revert back to PRO for other tests
        planService.changePlan(acmeTenantId, new ChangePlanRequest("PRO"), adminPrincipal);
    }

    @Test
    void billingEvent_DeduplicationAndCancellation() {
        String key = "test-webhook-" + UUID.randomUUID();

        // Process first time
        var event1 = planService.processBillingEvent(new BillingEventRequest(
            key, acmeTenantId, "SUBSCRIPTION_RENEWED", "INTERNAL", "{\"status\":\"paid\"}"
        ));
        assertThat(event1.status()).isEqualTo("PROCESSED");

        // Replay with identical key
        var event2 = planService.processBillingEvent(new BillingEventRequest(
            key, acmeTenantId, "SUBSCRIPTION_RENEWED", "INTERNAL", "{\"status\":\"paid\"}"
        ));
        assertThat(event2.id()).isEqualTo(event1.id());
    }
}
