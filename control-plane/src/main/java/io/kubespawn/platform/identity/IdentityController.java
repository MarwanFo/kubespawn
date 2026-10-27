package io.kubespawn.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.hibernate.validator.constraints.URL;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identities;
    private final AuthorizationService authorization;

    public IdentityController(IdentityService identities, AuthorizationService authorization) {
        this.identities = identities;
        this.authorization = authorization;
    }

    @GetMapping("/me")
    public IdentityService.Profile me(Authentication authentication) { return identities.current(authentication); }

    @PatchMapping("/me")
    public IdentityService.Profile update(@Valid @RequestBody ProfileUpdate body, Authentication authentication) {
        return identities.update(authentication, body.displayName(), body.avatarUrl());
    }

    @PostMapping("/onboarding/organizations")
    public IdentityService.Profile createOrganization(@Valid @RequestBody CreateOrganization body,
            Principal principal, HttpServletRequest request) {
        return identities.createOrganization(principal, body.name(), body.tenantKey(), requestId(request));
    }

    @PostMapping("/onboarding/invitations/accept")
    public IdentityService.Profile acceptInvitation(@Valid @RequestBody AcceptInvitation body,
            Principal principal, HttpServletRequest request) {
        return identities.acceptInvitation(principal, body.invitationToken(), requestId(request));
    }

    @GetMapping("/tenants/{tenantId}/permissions")
    public AuthorizationService.EffectivePermissions permissions(@PathVariable UUID tenantId, Principal principal) {
        return authorization.getEffectivePermissions(principal, tenantId);
    }

    @GetMapping("/admin/overview")
    public IdentityService.AdminOverview overview(Principal principal) { return identities.overview(principal); }

    @GetMapping("/admin/users")
    public List<IdentityService.UserSummary> users(Principal principal) { return identities.users(principal); }

    @GetMapping("/admin/tenants/{tenantId}/projects")
    public List<IdentityService.Project> projects(@PathVariable UUID tenantId, Principal principal) {
        identities.requirePlatformAdmin(principal);
        return identities.projects(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/projects")
    public List<IdentityService.Project> tenantProjects(@PathVariable UUID tenantId, Principal principal) {
        return identities.tenantProjects(principal, tenantId);
    }

    @PostMapping("/admin/users")
    public IdentityService.UserSummary provision(@Valid @RequestBody ProvisionUser body, Principal principal,
            HttpServletRequest request) {
        return identities.provision(principal, body.issuer(), body.subject(), body.email(), body.displayName(),
            body.tenantKey(), body.role(), body.platformAdmin(), requestId(request));
    }

    @PatchMapping("/admin/tenants/{tenantId}/members/{userId}")
    public IdentityService.UserSummary platformMembership(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @Valid @RequestBody MembershipUpdate body, Principal principal, HttpServletRequest request) {
        return identities.changeMembership(principal, tenantId, userId, body.role(), body.status(), requestId(request));
    }

    @GetMapping("/tenants/{tenantId}/members")
    public List<IdentityService.TenantMember> members(@PathVariable UUID tenantId, Principal principal) {
        authorization.requireRole(principal, tenantId, "DEVELOPER", "QA_REVIEWER", "PORTAL_ADMIN");
        return identities.listMembers(tenantId);
    }

    @PatchMapping("/tenants/{tenantId}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMemberRole(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @Valid @RequestBody RoleUpdate body, Principal principal, HttpServletRequest request) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        identities.updateMemberRole(principal, tenantId, userId, body.role(), requestId(request));
    }

    @PatchMapping("/tenants/{tenantId}/members/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMemberStatus(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @Valid @RequestBody StatusUpdate body, Principal principal, HttpServletRequest request) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        identities.updateMemberStatus(principal, tenantId, userId, body.status(), requestId(request));
    }

    @DeleteMapping("/tenants/{tenantId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID tenantId, @PathVariable UUID userId,
            Principal principal, HttpServletRequest request) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        identities.removeMember(principal, tenantId, userId, requestId(request));
    }

    @GetMapping("/tenants/{tenantId}/invitations")
    public List<IdentityService.Invitation> invitations(@PathVariable UUID tenantId, Principal principal) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        return identities.listInvitations(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.Invitation createInvitation(@PathVariable UUID tenantId,
            @Valid @RequestBody CreateInvitation body, Principal principal, HttpServletRequest request) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        return identities.createInvitation(principal, tenantId, body.email(), body.role(), requestId(request));
    }

    @DeleteMapping("/tenants/{tenantId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID tenantId, @PathVariable UUID invitationId,
            Principal principal, HttpServletRequest request) {
        authorization.requireRole(principal, tenantId, "PORTAL_ADMIN");
        identities.revokeInvitation(principal, tenantId, invitationId, requestId(request));
    }

    @GetMapping("/me/sessions")
    public List<IdentityService.SessionSummary> sessions(Principal principal, HttpServletRequest request) {
        var session = request.getSession(false);
        return identities.listSessions(principal, session != null ? session.getId() : null);
    }

    @PostMapping("/me/sessions/revoke-others")
    public Map<String, Integer> revokeOtherSessions(Principal principal, HttpServletRequest request) {
        var session = request.getSession(false);
        int revoked = identities.revokeOtherSessions(principal, session != null ? session.getId() : null, requestId(request));
        return Map.of("revokedCount", revoked);
    }

    public record ProfileUpdate(@NotBlank @Size(max=120) String displayName, @URL @Size(max=2048) String avatarUrl) {}
    public record ProvisionUser(
        @NotBlank @Size(max=512) String issuer,
        @NotBlank @Size(max=255) String subject,
        @Email @Size(max=320) String email,
        @NotBlank @Size(max=120) String displayName,
        @NotBlank @Pattern(regexp="^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$|^[a-z0-9]$") String tenantKey,
        @NotBlank @Pattern(regexp="DEVELOPER|QA_REVIEWER|PORTAL_ADMIN|VIEWER|MAINTAINER|TENANT_ADMIN") String role,
        boolean platformAdmin) {}
    public record MembershipUpdate(
        @NotBlank @Pattern(regexp="DEVELOPER|QA_REVIEWER|PORTAL_ADMIN|VIEWER|MAINTAINER|TENANT_ADMIN") String role,
        @NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String status) {}
    public record RoleUpdate(
        @NotBlank @Pattern(regexp="DEVELOPER|QA_REVIEWER|PORTAL_ADMIN") String role) {}
    public record StatusUpdate(
        @NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String status) {}
    public record CreateInvitation(
        @NotBlank @Email @Size(max=320) String email,
        @NotBlank @Pattern(regexp="DEVELOPER|QA_REVIEWER|PORTAL_ADMIN") String role) {}
    public record CreateOrganization(
        @NotBlank @Size(max=120) String name,
        @Pattern(regexp="^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$|^[a-z0-9]$|^$") String tenantKey) {}
    public record AcceptInvitation(
        @NotBlank @Size(max=256) String invitationToken) {}

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
