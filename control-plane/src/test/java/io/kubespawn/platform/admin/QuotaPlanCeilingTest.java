package io.kubespawn.platform.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.kubespawn.platform.admin.AdminModels.UpdateQuotaRequest;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QuotaPlanCeilingTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AuthorizationService authorization;

    private QuotaService quotaService;

    private final UUID tenantId = UUID.randomUUID();
    private final Principal actor = () -> "alice-admin";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        quotaService = new QuotaService(jdbc, authorization, null);
    }

    @Test
    void updateTenantQuotas_WhenExceedsPlanCeiling_ThrowsQuotaExceedsPlanCeiling() {
        // Plan has max_environments = 3 (Free Tier)
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tenantId)))
            .thenReturn(List.of(Map.of(
                "max_environments", 3,
                "cpu_millicores_quota", 4000,
                "memory_mb_quota", 8192,
                "storage_mb_quota", 20480,
                "max_ttl_hours", 24,
                "plan_name", "Starter / Free"
            )));

        // Admin requests 10 environments on Free tier
        UpdateQuotaRequest request = new UpdateQuotaRequest(10, 10, 4000, 8192, 20480, 24, 1);

        assertThatThrownBy(() -> quotaService.updateTenantQuotas(tenantId, request, actor))
            .isInstanceOf(LifecycleException.class)
            .hasMessageContaining("exceeds plan 'Starter / Free' ceiling of 3");
    }
}
