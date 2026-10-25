package io.kubespawn.platform.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
@AutoConfigureMockMvc
class LifecycleIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres@sha256:eee6c6e1b0894fa4fe0075eb054af9fd1cf39a35321c2d4110626cfe506411fb")
            .asCompatibleSubstituteFor("postgres"));
    private static final String TOKEN = "Bearer integration-test-token-with-at-least-32-characters";
    private static final String BODY = "{\"repository\":\"MarwanFo/kubespawn\",\"ref\":\"main\",\"ttlHours\":24}";
    @Autowired LifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void httpCreateReturnsAnAcceptedUnverifiedRequestWithVersionAndRequestId() throws Exception {
        mvc.perform(post("/api/v1/environments").header("Authorization", TOKEN)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted()).andExpect(header().string("ETag", "\"0\""))
            .andExpect(header().exists("Location")).andExpect(header().exists("X-Request-ID"))
            .andExpect(jsonPath("$.environment.repository").value("marwanfo/kubespawn"))
            .andExpect(jsonPath("$.environment.phase").value("WAITING_BUILD"))
            .andExpect(jsonPath("$.environment.desiredState").value("ACTIVE"))
            .andExpect(jsonPath("$.environment.requestedRef").value("main"))
            .andExpect(jsonPath("$.environment.requestedRevision").isEmpty())
            .andExpect(jsonPath("$.operationId").isNotEmpty());
    }

    @Test
    void createAtomicallyWritesSourceEnvironmentJobAuditAndOutbox() {
        var result = create("atomic-actor");
        UUID id = result.environment().id();
        assertThat(count("jobs", id)).isEqualTo(1);
        assertThat(count("audit_events", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT s.source_kind FROM environment_sources s JOIN environments e ON e.source_id=s.id WHERE e.id=?", String.class, id)).isEqualTo("MANUAL");
        assertThat(lifecycle.operation(result.operationId(), "atomic-actor").state()).isEqualTo("PENDING");
        assertThat(lifecycle.audit(id, "atomic-actor").getFirst().action()).isEqualTo("ENVIRONMENT_REQUESTED");
        assertThat(jdbc.queryForObject("""
            SELECT (a.details->>'expiresAt')::timestamptz=e.expires_at
            FROM audit_events a JOIN environments e ON e.id=a.environment_id WHERE e.id=?
            """, Boolean.class, id)).isTrue();
        assertThat(result.environment().namespace()).matches("ks-man-[a-f0-9]{32}");
    }

    @Test
    void retriesReuseTheOriginalEnvironmentAndOperation() {
        UUID key = UUID.randomUUID();
        var first = lifecycle.create(body(), key, "retry-actor", requestId());
        var again = lifecycle.create(body(), key, "retry-actor", requestId());
        assertThat(again).isEqualTo(first);
        assertThat(count("jobs", first.environment().id())).isEqualTo(1);
        assertThat(count("audit_events", first.environment().id())).isEqualTo(1);
    }

    @Test
    void conflictingKeyReuseIsRejectedWithoutExtraWrites() {
        UUID key = UUID.randomUUID();
        var original = lifecycle.create(body(), key, "conflict-actor", requestId());
        assertThatThrownBy(() -> lifecycle.create(new CreateEnvironmentRequest("example/other", "main", 24),
            key, "conflict-actor", requestId())).isInstanceOfSatisfying(LifecycleException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("jobs", original.environment().id())).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesCannotCreateTwoGenerations() throws Exception {
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        Callable<LifecycleService.Mutation> attempt = () -> {
            start.await();
            return lifecycle.create(body(), key, "parallel-actor", requestId());
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            var result = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(count("jobs", result.environment().id())).isEqualTo(1);
        }
    }

    @Test
    void theSameKeyIsIndependentForDifferentActors() {
        UUID key = UUID.randomUUID();
        var first = lifecycle.create(body(), key, "first-actor", requestId());
        var second = lifecycle.create(body(), key, "second-actor", requestId());
        assertThat(second.environment().id()).isNotEqualTo(first.environment().id());
    }

    @Test
    void auditFailureRollsBackEveryPartOfTheRequest() {
        long sourcesBefore = jdbc.queryForObject("SELECT count(*) FROM environment_sources", Long.class);
        UUID key = UUID.randomUUID();
        jdbc.execute("ALTER TABLE audit_events ADD CONSTRAINT test_reject_actor CHECK (actor <> 'rollback-actor')");
        try {
            assertThatThrownBy(() -> lifecycle.create(body(), key, "rollback-actor", requestId()))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM api_idempotency WHERE actor='rollback-actor'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM environments WHERE owner_actor='rollback-actor'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM environment_sources", Long.class)).isEqualTo(sourcesBefore);
        } finally { jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT test_reject_actor"); }
        assertThat(lifecycle.create(body(), key, "rollback-actor", requestId()).environment().phase()).isEqualTo("WAITING_BUILD");
    }

    @Test
    void extensionAdvancesTheVersionAndRejectsStaleOrExcessiveUpdates() {
        var created = create("extend-actor").environment();
        var changed = lifecycle.extend(created.id(), 0, created.expiresAt().plus(Duration.ofHours(4)), "extend-actor", requestId());
        assertThat(changed.environment().intentVersion()).isEqualTo(1);
        assertThat(changed.environment().expiresAt()).isAfter(created.expiresAt());
        assertThatThrownBy(() -> lifecycle.extend(created.id(), 0, created.expiresAt().plus(Duration.ofHours(8)), "extend-actor", requestId()))
            .isInstanceOfSatisfying(LifecycleException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(() -> lifecycle.extend(created.id(), 1, created.createdAt().plus(Duration.ofHours(73)), "extend-actor", requestId()))
            .isInstanceOfSatisfying(LifecycleException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(count("jobs", created.id())).isEqualTo(2);
    }

    @Test
    void deletionIsIdempotentAndCannotBeUndoneByExtension() {
        var created = create("delete-actor").environment();
        var deleted = lifecycle.delete(created.id(), 0, "delete-actor", requestId());
        assertThat(deleted.environment().desiredState()).isEqualTo("ABSENT");
        assertThat(deleted.environment().phase()).isEqualTo("TERMINATING");
        assertThat(lifecycle.delete(created.id(), 0, "delete-actor", requestId())).isEqualTo(deleted);
        assertThatThrownBy(() -> lifecycle.extend(created.id(), 1, created.expiresAt().plusSeconds(3600), "delete-actor", requestId()))
            .isInstanceOfSatisfying(LifecycleException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("jobs", created.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT destroyed_at IS NULL FROM environments WHERE id=?", Boolean.class, created.id())).isTrue();
    }

    @Test
    void anotherActorCannotReadOrMutateTheRequestOrItsOperation() {
        var created = create("owner-actor");
        UUID id = created.environment().id();
        List<Runnable> attempts = List.of(
            () -> lifecycle.get(id, "other-actor"),
            () -> lifecycle.delete(id, 0, "other-actor", requestId()),
            () -> lifecycle.extend(id, 0, created.environment().expiresAt().plusSeconds(3600), "other-actor", requestId()),
            () -> lifecycle.audit(id, "other-actor"),
            () -> lifecycle.operation(created.operationId(), "other-actor")
        );
        for (Runnable attempt : attempts) {
            assertThatThrownBy(attempt::run).isInstanceOfSatisfying(LifecycleException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void expiredRequestsCannotBeExtendedAndAreMarkedAbsentOnlyOnce() {
        var created = create("expired-actor").environment();
        jdbc.update("UPDATE environments SET created_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE id=?", created.id());
        assertThatThrownBy(() -> lifecycle.extend(created.id(), 0, created.expiresAt(), "expired-actor", requestId()))
            .isInstanceOfSatisfying(LifecycleException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(lifecycle.expireDueBatch()).isEqualTo(1);
        assertThat(lifecycle.expireDueBatch()).isZero();
        assertThat(lifecycle.get(created.id(), "expired-actor").desiredState()).isEqualTo("ABSENT");
        assertThat(lifecycle.audit(created.id(), "expired-actor").getFirst().action()).isEqualTo("TTL_EXPIRED");
        assertThat(count("jobs", created.id())).isEqualTo(2);
    }

    @Test
    void simultaneousExtensionAndDeletionHaveOneVersionedWinner() throws Exception {
        var created = create("race-actor").environment();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var extend = executor.submit(() -> race(start, () -> lifecycle.extend(created.id(), 0,
                created.expiresAt().plusSeconds(3600), "race-actor", requestId())));
            var delete = executor.submit(() -> race(start, () -> lifecycle.delete(created.id(), 0, "race-actor", requestId())));
            start.countDown();
            assertThat(List.of(extend.get(15, TimeUnit.SECONDS), delete.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(lifecycle.get(created.id(), "race-actor").intentVersion()).isEqualTo(1);
        assertThat(count("jobs", created.id())).isEqualTo(2);
    }

    @Test
    void httpRejectsInvalidInputAndMissingIdempotencyWithoutCreatingRequests() throws Exception {
        mvc.perform(post("/api/v1/environments").header("Authorization", TOKEN)
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        for (String invalid : List.of(
            "{\"repository\":\"https://github.com/example/app\",\"ref\":\"main\",\"ttlHours\":24}",
            "{\"repository\":\"example/app\",\"ref\":\"main\",\"ttlHours\":0}",
            "{\"repository\":\"example/app\",\"ref\":\"main\",\"ttlHours\":25}",
            "{\"repository\":\"example/app\",\"ref\":\"\",\"ttlHours\":24}")) {
            mvc.perform(post("/api/v1/environments").header("Authorization", TOKEN)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/v1/environments").header("Idempotency-Key", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
    }

    @Test
    void httpMutationsRequireAQuotedVersion() throws Exception {
        var created = create("local-developer").environment();
        mvc.perform(delete("/api/v1/environments/" + created.id()).header("Authorization", TOKEN))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(delete("/api/v1/environments/" + created.id()).header("Authorization", TOKEN).header("If-Match", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/environments/" + created.id() + "/expiry").header("Authorization", TOKEN)
            .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresAt\":\"" + created.expiresAt().plusSeconds(3600) + "\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void localProxyRejectsCrossOriginMutationsButAcceptsItsOwnOrigin() throws Exception {
        mvc.perform(post("/api/v1/environments").header("Authorization", TOKEN)
            .header("Host", "localhost:5173").header("Origin", "https://untrusted.example")
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/environments").header("Authorization", TOKEN)
            .header("Host", "localhost:5173").header("Origin", "http://localhost:5173")
            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted());
    }

    private LifecycleService.Mutation create(String actor) {
        return lifecycle.create(body(), UUID.randomUUID(), actor, requestId());
    }

    private static CreateEnvironmentRequest body() { return new CreateEnvironmentRequest("MarwanFo/kubespawn", "main", 24); }
    private static String requestId() { return UUID.randomUUID().toString(); }

    private int count(String table, UUID id) {
        // Only fixed, test-owned table names are passed to this helper.
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE environment_id=?", Integer.class, id);
    }

    private boolean race(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try { action.run(); return true; }
        catch (LifecycleException conflict) {
            assertThat(conflict.status()).isIn(HttpStatus.CONFLICT, HttpStatus.PRECONDITION_FAILED);
            return false;
        }
    }
}
