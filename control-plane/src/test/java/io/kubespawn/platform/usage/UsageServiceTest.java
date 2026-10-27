package io.kubespawn.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.usage.UsageModels.PlanDto;
import io.kubespawn.platform.usage.UsageModels.TenantSubscriptionDto;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class UsageServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AuthorizationService authorization;

    private ObjectMapper objectMapper;
    private UsageService usageService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final Principal actor = () -> "alice-admin";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        usageService = new UsageService(jdbc, authorization, objectMapper);
    }

    @Test
    void openInterval_WhenNotExists_InsertsNewInterval() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(environmentId))).thenReturn(0);

        usageService.openInterval(tenantId, projectId, environmentId, "test-env", 1000, 2048, 5120);

        verify(jdbc).update(anyString(), any(UUID.class), eq(tenantId), eq(projectId), eq(environmentId),
            eq("test-env"), eq(1000), eq(2048), eq(5120));
    }

    @Test
    void openInterval_WhenAlreadyActive_DoesNotDuplicate() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(environmentId))).thenReturn(1);

        usageService.openInterval(tenantId, projectId, environmentId, "test-env", 1000, 2048, 5120);

        verify(jdbc, never()).update(anyString(), any(UUID.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void closeInterval_ExecutesUpdateWithCalculatedMetrics() {
        usageService.closeInterval(environmentId);

        verify(jdbc).update(anyString(), eq(environmentId));
    }

    @Test
    void reconcileOrphanIntervals_ExecutesReconciliationQuery() {
        usageService.reconcileOrphanIntervals();

        verify(jdbc).update(anyString());
    }

    @Test
    void exportUsageCsv_GeneratesValidCsvHeadersAndData() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenantId)))
            .thenReturn(List.of(
                new UsageModels.UsageIntervalDto(
                    UUID.randomUUID(), tenantId, projectId, environmentId, "web-preview",
                    1000, 2048, 5120, Instant.parse("2026-10-26T10:00:00Z"),
                    Instant.parse("2026-10-26T12:00:00Z"), 7200L, 2.0, 4.0, 11, "CLOSED"
                )
            ));

        String csv = usageService.exportUsageCsv(tenantId, null, null, actor);

        assertThat(csv).contains("Interval ID,Project ID,Environment ID,Environment Name");
        assertThat(csv).contains("\"web-preview\"");
        assertThat(csv).contains("CLOSED");
        assertThat(csv).contains("2.0");
    }
}
