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
import org.hibernate.validator.constraints.URL;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identities;

    public IdentityController(IdentityService identities) { this.identities = identities; }

    @GetMapping("/me")
    public IdentityService.Profile me(Authentication authentication) { return identities.current(authentication); }

    @PatchMapping("/me")
    public IdentityService.Profile update(@Valid @RequestBody ProfileUpdate body, Authentication authentication) {
        return identities.update(authentication, body.displayName(), body.avatarUrl());
    }

    @GetMapping("/admin/overview")
    public IdentityService.AdminOverview overview(Principal principal) { return identities.overview(principal); }

    @GetMapping("/admin/users")
    public List<IdentityService.UserSummary> users(Principal principal) { return identities.users(principal); }

    @PostMapping("/admin/users")
    public IdentityService.UserSummary provision(@Valid @RequestBody ProvisionUser body, Principal principal,
            HttpServletRequest request) {
        return identities.provision(principal, body.issuer(), body.subject(), body.email(), body.displayName(),
            body.tenantKey(), body.role(), body.platformAdmin(), requestId(request));
    }

    @PatchMapping("/admin/tenants/{tenantId}/members/{userId}")
    public IdentityService.UserSummary membership(@PathVariable UUID tenantId, @PathVariable UUID userId,
            @Valid @RequestBody MembershipUpdate body, Principal principal, HttpServletRequest request) {
        return identities.changeMembership(principal, tenantId, userId, body.role(), body.status(), requestId(request));
    }

    public record ProfileUpdate(@NotBlank @Size(max=120) String displayName, @URL @Size(max=2048) String avatarUrl) {}
    public record ProvisionUser(
        @NotBlank @Size(max=512) String issuer,
        @NotBlank @Size(max=255) String subject,
        @Email @Size(max=320) String email,
        @NotBlank @Size(max=120) String displayName,
        @NotBlank @Pattern(regexp="^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$|^[a-z0-9]$") String tenantKey,
        @NotBlank @Pattern(regexp="VIEWER|DEVELOPER|MAINTAINER|TENANT_ADMIN") String role,
        boolean platformAdmin) {}
    public record MembershipUpdate(
        @NotBlank @Pattern(regexp="VIEWER|DEVELOPER|MAINTAINER|TENANT_ADMIN") String role,
        @NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String status) {}

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
