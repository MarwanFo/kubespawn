package io.kubespawn.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kubespawn.platform.api.EnvironmentService;
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
    "kubespawn.lifecycle.expiry.enabled=false", "kubespawn.worker.enabled=false"
})
class PlatformDatabaseTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
            .asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;
    @Autowired EnvironmentService environments;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void flywayCreatesTheDurableFoundation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
            .isEqualTo(3);
        for (String table : new String[] {"environment_sources", "environments", "jobs", "outbox_events", "audit_events"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table)).isTrue();
        }
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
        assertThat(environments.list(100)).anySatisfy(environment -> {
            assertThat(environment.id()).isEqualTo(id);
            assertThat(environment.repository()).isEqualTo("example/sample-app");
            assertThat(environment.phase()).isEqualTo("WAITING_BUILD");
        });
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
