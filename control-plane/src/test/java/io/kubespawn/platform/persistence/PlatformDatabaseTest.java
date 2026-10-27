package io.kubespawn.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kubespawn.platform.api.EnvironmentService;
import io.kubespawn.platform.identity.IdentityService;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "spring.profiles.active=local", "KUBESPAWN_DEV_TOKEN=integration-test-token-with-at-least-32-characters",
    "kubespawn.lifecycle.expiry.enabled=false", "kubespawn.worker.enabled=false", "kubespawn.github.enabled=false"
})
class PlatformDatabaseTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
            .asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;
    @Autowired EnvironmentService environments;
    @Autowired IdentityService identities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void flywayCreatesTheDurableFoundation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
            .isEqualTo(16);
        for (String table : new String[] {"environment_sources", "environments", "jobs", "outbox_events", "audit_events",
                "app_users", "user_identities", "tenants", "tenant_memberships", "projects", "project_memberships",
                "spring_session", "spring_session_attributes", "tenant_invitations", "preview_exchange_tickets",
                "review_requests", "review_assignments", "review_test_checklists", "review_comments", "review_decisions",
                "tenant_quotas", "project_quotas", "recovery_operations",
                "notification_preferences", "notifications", "notification_deliveries",
                "plans", "tenant_subscriptions", "usage_intervals", "billing_events"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table)).isTrue();
        }
    }

    @Test
    void localAdministratorHasPersistedProfileAndTenantMembership() {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "local-developer", null);
        var profile = identities.current(authentication);
        assertThat(profile.displayName()).isEqualTo("Local Administrator");
        assertThat(profile.platformAdmin()).isTrue();
        assertThat(profile.onboardingStatus()).isEqualTo("COMPLETED");
        assertThat(profile.memberships()).singleElement().satisfies(membership -> {
            assertThat(membership.tenantKey()).isEqualTo("local");
            assertThat(membership.role()).isEqualTo("PORTAL_ADMIN");
        });
        var localTenantId = profile.memberships().getFirst().tenantId();
        assertThat(identities.projects(localTenantId)).singleElement().satisfies(project -> {
            assertThat(project.projectKey()).isEqualTo("default");
            assertThat(project.name()).isEqualTo("Default Project");
        });
    }

    @Test
    void administratorCanProvisionAndSuspendTenantMembership() {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "local-developer", null);
        String subject = "test-subject-" + UUID.randomUUID();
        var created = identities.provision(authentication, "https://issuer.test", subject,
            subject + "@example.test", "Test Developer", "local", "DEVELOPER", false, UUID.randomUUID().toString());
        assertThat(created.memberships()).singleElement().extracting(IdentityService.Membership::role)
            .isEqualTo("DEVELOPER");
        var membership = created.memberships().getFirst();
        var changed = identities.changeMembership(authentication, membership.tenantId(), created.id(),
            "QA_REVIEWER", "SUSPENDED", UUID.randomUUID().toString());
        assertThat(changed.memberships()).singleElement().satisfies(value -> {
            assertThat(value.role()).isEqualTo("QA_REVIEWER");
            assertThat(value.status()).isEqualTo("SUSPENDED");
        });
    }

    @Test
    void phase01DeterministicFixturesAreSeededAndDistinct() {
        // Acme Tenant verification
        UUID acmeTenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var acmeProjects = identities.projects(acmeTenantId);
        assertThat(acmeProjects).extracting(IdentityService.Project::projectKey)
            .containsExactlyInAnyOrder("web-app", "backend-api");

        // Globex Tenant verification
        UUID globexTenantId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var globexProjects = identities.projects(globexTenantId);
        assertThat(globexProjects).singleElement().extracting(IdentityService.Project::projectKey)
            .isEqualTo("core-platform");

        // All 3 target SaaS roles exist in Acme
        var acmeRoles = jdbc.queryForList("""
            SELECT role FROM tenant_memberships WHERE tenant_id=? AND status='ACTIVE'
            """, String.class, acmeTenantId);
        assertThat(acmeRoles).containsExactlyInAnyOrder("PORTAL_ADMIN", "DEVELOPER", "QA_REVIEWER");

        // Suspended user verification
        var suspendedStatus = jdbc.queryForObject(
            "SELECT status FROM app_users WHERE id='10000000-0000-0000-0000-000000000104'", String.class);
        assertThat(suspendedStatus).isEqualTo("SUSPENDED");

        // Onboarding user verification (PENDING_ONBOARDING and 0 memberships)
        var onboardingUser = jdbc.queryForMap(
            "SELECT onboarding_status, status FROM app_users WHERE id='30000000-0000-0000-0000-000000000101'");
        assertThat(onboardingUser.get("onboarding_status")).isEqualTo("PENDING_ONBOARDING");
        assertThat(onboardingUser.get("status")).isEqualTo("ACTIVE");

        var newbieMemberships = jdbc.queryForObject(
            "SELECT count(*) FROM tenant_memberships WHERE user_id='30000000-0000-0000-0000-000000000101'", Long.class);
        assertThat(newbieMemberships).isEqualTo(0L);
    }

    @Test
    void phase07GitHubProjectRepositoriesAreConfiguredAndScoped() {
        UUID acmeTenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID acmeProjectId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        var acmeRepo = jdbc.queryForMap("""
            SELECT full_name, allowed_branch, policy_version, enabled
            FROM github_repositories
            WHERE tenant_id=? AND project_id=?
            """, acmeTenantId, acmeProjectId);

        assertThat(acmeRepo.get("full_name")).isEqualTo("acme/web-frontend");
        assertThat(acmeRepo.get("allowed_branch")).isEqualTo("main");
        assertThat(((Number) acmeRepo.get("policy_version")).longValue()).isEqualTo(1L);
        assertThat(acmeRepo.get("enabled")).isEqualTo(true);

        UUID globexTenantId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID globexProjectId = UUID.fromString("20000000-0000-0000-0000-000000000011");

        var globexRepo = jdbc.queryForMap("""
            SELECT full_name, allowed_branch, policy_version, enabled
            FROM github_repositories
            WHERE tenant_id=? AND project_id=?
            """, globexTenantId, globexProjectId);

        assertThat(globexRepo.get("full_name")).isEqualTo("globex/portal");
        assertThat(((Number) globexRepo.get("policy_version")).longValue()).isEqualTo(1L);
    }

    @Test
    void phase08ScopedPreviewEnvironmentsAreConfigured() {
        UUID acmeTenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID acmeProjectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID seededEnvId = UUID.fromString("10000000-0000-0000-0000-000000000031");

        var env = jdbc.queryForMap("""
            SELECT id, tenant_id, project_id, repository_name, namespace, phase, owner_actor
            FROM environments WHERE id=?
            """, seededEnvId);

        assertThat(env.get("tenant_id")).isEqualTo(acmeTenantId);
        assertThat(env.get("project_id")).isEqualTo(acmeProjectId);
        assertThat(env.get("repository_name")).isEqualTo("acme/web-frontend");
        assertThat(env.get("phase")).isEqualTo("READY");
        assertThat(env.get("owner_actor")).isEqualTo("acme-dev");

        var auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE environment_id=?", Long.class, seededEnvId);
        assertThat(auditCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void oneLiveGenerationIsEnforcedButHistoryCanBeRetained() {
        UUID source = createManualSource();
        UUID first = UUID.randomUUID();
        createEnvironment(first, source);
        assertThatThrownBy(() -> createEnvironment(UUID.randomUUID(), source))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE environments SET desired_state='ABSENT', phase='DESTROYED', destroyed_at=now() WHERE id=?", first);
        createEnvironment(UUID.randomUUID(), source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments WHERE source_id=?", Integer.class, source)).isEqualTo(2);
    }

    @Test
    void inventoryMapsRealPostgresRowsThroughJpa() {
        UUID id = UUID.randomUUID();
        createEnvironment(id, createManualSource());
        assertThat(environments.list(100, new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "local-developer", null))).anySatisfy(environment -> {
            assertThat(environment.id()).isEqualTo(id);
            assertThat(environment.repository()).isEqualTo("example/sample-app");
            assertThat(environment.phase()).isEqualTo("WAITING_BUILD");
        });
    }

    @Test
    void phase09PreviewTicketsAndDiagnosticsAreConfigured() {
        UUID envId = UUID.fromString("10000000-0000-0000-0000-000000000031");
        UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID ticket = UUID.randomUUID();

        // Insert ticket
        jdbc.update("""
            INSERT INTO preview_exchange_tickets (
                ticket, environment_id, tenant_id, project_id, actor, expires_at, consumed
            ) VALUES (?, ?, ?, ?, 'acme-dev', clock_timestamp() + INTERVAL '60 seconds', false)
            """, ticket, envId, tenantId, projectId);

        // Verify active
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM preview_exchange_tickets
                WHERE ticket=? AND environment_id=? AND consumed=false AND expires_at > clock_timestamp()
            )
            """, Boolean.class, ticket, envId);
        assertThat(exists).isTrue();

        // Atomically consume
        int consumed = jdbc.update("""
            UPDATE preview_exchange_tickets SET consumed=true, consumed_at=clock_timestamp()
            WHERE ticket=? AND consumed=false AND expires_at > clock_timestamp()
            """, ticket);
        assertThat(consumed).isEqualTo(1);

        // Replay attempt fails (already consumed)
        int replayed = jdbc.update("""
            UPDATE preview_exchange_tickets SET consumed=true, consumed_at=clock_timestamp()
            WHERE ticket=? AND consumed=false AND expires_at > clock_timestamp()
            """, ticket);
        assertThat(replayed).isEqualTo(0);
    }

    @Test
    void phase10ReviewWorkflowAndFixturesAreConfigured() {
        UUID reviewId = UUID.fromString("10000000-0000-0000-0000-000000000041");
        UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        // Verify review request fixture exists
        var status = jdbc.queryForObject(
            "SELECT status FROM review_requests WHERE id=? AND tenant_id=? AND project_id=?",
            String.class, reviewId, tenantId, projectId
        );
        assertThat(status).isEqualTo("PENDING");

        // Verify assignments for acme-qa and local-developer
        var reviewers = jdbc.queryForList(
            "SELECT reviewer_actor FROM review_assignments WHERE review_request_id=? AND status='ASSIGNED' ORDER BY reviewer_actor",
            String.class, reviewId
        );
        assertThat(reviewers).contains("acme-qa", "local-developer");

        // Verify test checklists (4 items)
        Integer checklistCount = jdbc.queryForObject(
            "SELECT count(*) FROM review_test_checklists WHERE review_request_id=?",
            Integer.class, reviewId
        );
        assertThat(checklistCount).isEqualTo(4);

        // Verify comment fixture
        Integer commentCount = jdbc.queryForObject(
            "SELECT count(*) FROM review_comments WHERE review_request_id=?",
            Integer.class, reviewId
        );
        assertThat(commentCount).isGreaterThanOrEqualTo(1);

        // Verify immutable decision insert and status update
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO review_decisions (id, review_request_id, decision, decision_actor, reviewed_revision, notes)
            VALUES (?, ?, 'APPROVED', 'acme-qa', 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4', 'Verified in test')
        """, decisionId, reviewId);

        jdbc.update("UPDATE review_requests SET status='APPROVED' WHERE id=?", reviewId);
        var updatedStatus = jdbc.queryForObject("SELECT status FROM review_requests WHERE id=?", String.class, reviewId);
        assertThat(updatedStatus).isEqualTo("APPROVED");

        // Restore fixture status back to PENDING for idempotent test runs
        jdbc.update("DELETE FROM review_decisions WHERE id=?", decisionId);
        jdbc.update("UPDATE review_requests SET status='PENDING' WHERE id=?", reviewId);
    }

    @Test
    void phase11QuotasAndRecoveryFixturesAreConfigured() {
        UUID acmeTenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID acmeProjectId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        // 1. Verify tenant quotas for Acme
        var tenantQuota = jdbc.queryForMap("""
            SELECT max_environments, max_queued_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version
            FROM tenant_quotas WHERE tenant_id=?
            """, acmeTenantId);
        assertThat(((Number) tenantQuota.get("max_environments")).intValue()).isEqualTo(5);
        assertThat(((Number) tenantQuota.get("cpu_millicores_quota")).intValue()).isEqualTo(8000);
        assertThat(((Number) tenantQuota.get("memory_mb_quota")).intValue()).isEqualTo(16384);
        assertThat(((Number) tenantQuota.get("policy_version")).intValue()).isEqualTo(1);

        // 2. Verify project quotas for Acme Web App
        var projectQuota = jdbc.queryForMap("""
            SELECT max_environments, cpu_millicores_quota, memory_mb_quota, storage_mb_quota, max_ttl_hours, policy_version
            FROM project_quotas WHERE project_id=?
            """, acmeProjectId);
        assertThat(((Number) projectQuota.get("max_environments")).intValue()).isEqualTo(4);
        assertThat(((Number) projectQuota.get("cpu_millicores_quota")).intValue()).isEqualTo(6000);
        assertThat(((Number) projectQuota.get("memory_mb_quota")).intValue()).isEqualTo(12288);

        // 3. Verify deterministic failed environment fixture
        UUID failedEnvId = UUID.fromString("10000000-0000-0000-0000-000000000032");
        var failedEnv = jdbc.queryForMap("""
            SELECT id, tenant_id, project_id, repository_name, phase, desired_state, cpu_millicores, memory_mb, storage_mb
            FROM environments WHERE id=?
            """, failedEnvId);
        assertThat(failedEnv.get("phase")).isEqualTo("FAILED");
        assertThat(failedEnv.get("desired_state")).isEqualTo("ACTIVE");
        assertThat(((Number) failedEnv.get("cpu_millicores")).intValue()).isEqualTo(1000);
        assertThat(((Number) failedEnv.get("memory_mb")).intValue()).isEqualTo(2048);

        // 4. Verify deterministic failed job fixture
        UUID failedJobId = UUID.fromString("10000000-0000-0000-0000-000000000071");
        var failedJob = jdbc.queryForMap("""
            SELECT id, environment_id, state, attempts, failure_count, last_error
            FROM jobs WHERE id=?
            """, failedJobId);
        assertThat(failedJob.get("state")).isEqualTo("FAILED");
        assertThat(((Number) failedJob.get("failure_count")).intValue()).isEqualTo(3);
        assertThat((String) failedJob.get("last_error")).contains("Argo CD");

        // 5. Verify audit_events tenant scoping
        UUID auditId = UUID.fromString("10000000-0000-0000-0000-000000000081");
        var auditEvent = jdbc.queryForMap("""
            SELECT id, tenant_id, project_id, environment_id, action
            FROM audit_events WHERE id=?
            """, auditId);
        assertThat(auditEvent.get("tenant_id")).isEqualTo(acmeTenantId);
        assertThat(auditEvent.get("project_id")).isEqualTo(acmeProjectId);
        assertThat(auditEvent.get("action")).isEqualTo("OPERATION_FAILED");
    }

    private UUID createManualSource() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO environment_sources(id, tenant_key, project_key, repository_name,
                source_kind, manual_request_id, requested_ref, source_state)
            VALUES (?, 'local', 'sample', 'example/sample-app', 'MANUAL', ?, 'main', 'MANUAL')
            """, id, UUID.randomUUID());
        return id;
    }

    private void createEnvironment(UUID id, UUID source) {
        jdbc.update("""
            INSERT INTO environments(id, source_id, repository_name, cluster_id, namespace,
                desired_state, phase, expires_at)
            VALUES (?, ?, 'example/sample-app', 'local', ?, 'ACTIVE', 'WAITING_BUILD', now() + interval '24 hours')
            """, id, source, "ks-man-" + id.toString().substring(0, 12));
    }
}
