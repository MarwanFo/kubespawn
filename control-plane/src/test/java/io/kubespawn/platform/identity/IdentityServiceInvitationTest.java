package io.kubespawn.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;

class IdentityServiceInvitationTest {
    private JdbcTemplate jdbc;
    private IdentityService service;
    private Authentication principal;

    private final UUID tenantAcme = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID adminUserId = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private final UUID targetUserId = UUID.fromString("10000000-0000-0000-0000-000000000102");

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new IdentityService(jdbc);
        principal = mock(Authentication.class);
        when(principal.getName()).thenReturn("admin@acme.test");
    }

    private void mockCurrentProfile(UUID userId, String email, String role) {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString(), anyString())).thenReturn(List.of(
            new Object() {
                UUID id = userId;
                String userEmail = email;
                String displayName = "Test Admin";
                String avatarUrl = null;
                String status = "ACTIVE";
                String onboardingStatus = "COMPLETED";
                int securityVersion = 1;
                boolean platformAdmin = false;
                Instant createdAt = Instant.now();
                Instant lastSeenAt = Instant.now();
            }
        ));
    }

    @Test
    void sha256HexProducesDeterministicHashes() {
        String hash1 = IdentityService.sha256Hex("acme-dev-invite-token-32-chars-long");
        assertThat(hash1).isEqualTo("6dcf9171e586e83f56ac34f2ae2a70ca117a8a816285947ff07cc99ac1b5c8cd");
    }

    @Test
    void lastAdminProtectionPreventsDemotingSoleAdmin() {
        // Target is admin
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq(targetUserId)))
            .thenReturn(true);
        // Only 1 admin in organization
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenantAcme)))
            .thenReturn(1);

        assertThatThrownBy(() -> service.updateMemberRole(principal, tenantAcme, targetUserId, "DEVELOPER", "req-1"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.CONFLICT)
            .matches(e -> ((LifecycleException) e).code().equals("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void lastAdminProtectionPreventsSuspendingSoleAdmin() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq(targetUserId)))
            .thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenantAcme)))
            .thenReturn(1);

        assertThatThrownBy(() -> service.updateMemberStatus(principal, tenantAcme, targetUserId, "SUSPENDED", "req-2"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.CONFLICT)
            .matches(e -> ((LifecycleException) e).code().equals("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void lastAdminProtectionPreventsRemovingSoleAdmin() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq(targetUserId)))
            .thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenantAcme)))
            .thenReturn(1);

        assertThatThrownBy(() -> service.removeMember(principal, tenantAcme, targetUserId, "req-3"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.CONFLICT)
            .matches(e -> ((LifecycleException) e).code().equals("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void lastAdminAllowsDemotingWhenMultipleAdminsExist() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq(targetUserId)))
            .thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tenantAcme)))
            .thenReturn(2);
        when(jdbc.update(anyString(), eq("DEVELOPER"), eq(tenantAcme), eq(targetUserId)))
            .thenReturn(1);

        service.updateMemberRole(principal, tenantAcme, targetUserId, "DEVELOPER", "req-4");
    }

    @Test
    void createInvitationRejectsDuplicateEmailWhenAlreadyMember() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq("dev@acme.test")))
            .thenReturn(true);

        assertThatThrownBy(() -> service.createInvitation(principal, tenantAcme, "dev@acme.test", "DEVELOPER", "req-5"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.CONFLICT)
            .matches(e -> ((LifecycleException) e).code().equals("MEMBER_ALREADY_EXISTS"));
    }

    @Test
    void createInvitationRejectsWhenPendingInvitationAlreadyExists() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantAcme), eq("newbie@acme.test")))
            .thenReturn(false) // not member
            .thenReturn(true); // already has pending invite

        assertThatThrownBy(() -> service.createInvitation(principal, tenantAcme, "newbie@acme.test", "DEVELOPER", "req-6"))
            .isInstanceOf(LifecycleException.class)
            .matches(e -> ((LifecycleException) e).status() == HttpStatus.CONFLICT)
            .matches(e -> ((LifecycleException) e).code().equals("INVITATION_ALREADY_EXISTS"));
    }
}
