package io.kubespawn.platform.preview;

import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.provisioning.ProvisioningGateway;
import io.kubespawn.platform.provisioning.ProvisioningSettings;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreviewExchangeService {
    public record TicketResponse(
        UUID ticket,
        String exchangeUrl,
        String previewPath,
        Instant expiresAt
    ) {}

    public record ExchangeResult(
        String token,
        String previewPath,
        String previewUrl,
        long maxAgeSeconds
    ) {}

    private final JdbcTemplate jdbc;
    private final ProvisioningSettings settings;
    private final ProvisioningGateway gateway;

    public PreviewExchangeService(JdbcTemplate jdbc, ProvisioningSettings settings, ProvisioningGateway gateway) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.gateway = gateway;
    }

    @Transactional
    public TicketResponse createTicket(UUID tenantId, UUID projectId, UUID environmentId, String actor) {
        Boolean ready = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM environments
                WHERE id = ? AND tenant_id = ? AND project_id = ?
                  AND desired_state = 'ACTIVE' AND phase = 'READY'
                  AND expires_at > clock_timestamp()
            )
            """, Boolean.class, environmentId, tenantId, projectId);

        if (!Boolean.TRUE.equals(ready)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "PREVIEW_NOT_READY",
                "Preview is not ready or has already expired.");
        }

        UUID ticket = UUID.randomUUID();
        Instant ticketExpiresAt = Instant.now().plus(Duration.ofSeconds(60));

        jdbc.update("""
            INSERT INTO preview_exchange_tickets (
                ticket, environment_id, tenant_id, project_id, actor, expires_at, consumed
            ) VALUES (?, ?, ?, ?, ?, ?, false)
            """, ticket, environmentId, tenantId, projectId, actor, Timestamp.from(ticketExpiresAt));

        String exchangeUrl = String.format("/api/v1/tenants/%s/projects/%s/environments/%s/preview-exchange?ticket=%s",
            tenantId, projectId, environmentId, ticket);
        String previewPath = settings.previewPath(environmentId);

        return new TicketResponse(ticket, exchangeUrl, previewPath, ticketExpiresAt);
    }

    @Transactional
    public ExchangeResult consumeTicket(UUID tenantId, UUID projectId, UUID environmentId, UUID ticket, String actor) {
        // Atomic consumption preventing replaying or re-use
        int consumed = jdbc.update("""
            UPDATE preview_exchange_tickets
            SET consumed = true, consumed_at = clock_timestamp()
            WHERE ticket = ? AND environment_id = ? AND tenant_id = ? AND project_id = ?
              AND consumed = false AND expires_at > clock_timestamp()
            """, ticket, environmentId, tenantId, projectId);

        if (consumed == 0) {
            throw new LifecycleException(HttpStatus.UNAUTHORIZED, "INVALID_PREVIEW_TICKET",
                "The preview exchange ticket is invalid, expired, or has already been used.");
        }

        // Fetch environment namespace and expiry
        var envData = jdbc.query("""
            SELECT namespace, expires_at FROM environments
            WHERE id = ? AND tenant_id = ? AND project_id = ?
            """, (rs, row) -> new Object[] {
                rs.getString("namespace"),
                rs.getTimestamp("expires_at").toInstant()
            }, environmentId, tenantId, projectId)
            .stream().findFirst().orElseThrow(() ->
                new LifecycleException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Environment not found."));

        String namespace = (String) envData[0];
        Instant expiresAt = (Instant) envData[1];

        String token = gateway.previewAccessToken(namespace);
        long maxAge = Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds());
        String previewPath = settings.previewPath(environmentId);
        String previewUrl = settings.previewUrl(environmentId);

        return new ExchangeResult(token, previewPath, previewUrl, maxAge);
    }
}
