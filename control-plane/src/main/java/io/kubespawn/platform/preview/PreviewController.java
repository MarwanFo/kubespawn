package io.kubespawn.platform.preview;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.provisioning.ProvisioningModels;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/projects/{projectId}")
public class PreviewController {
    private final PreviewAccessPolicy accessPolicy;
    private final PreviewExchangeService exchangeService;
    private final PreviewDiagnosticsService diagnosticsService;
    private final AuthorizationService authorization;

    public PreviewController(
            PreviewAccessPolicy accessPolicy,
            PreviewExchangeService exchangeService,
            PreviewDiagnosticsService diagnosticsService,
            AuthorizationService authorization) {
        this.accessPolicy = accessPolicy;
        this.exchangeService = exchangeService;
        this.diagnosticsService = diagnosticsService;
        this.authorization = authorization;
    }

    @PostMapping("/environments/{id}/preview-ticket")
    public ResponseEntity<PreviewExchangeService.TicketResponse> createTicket(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            Principal actor) {
        accessPolicy.requirePreviewAccess(actor, tenantId, projectId, id);
        var response = exchangeService.createTicket(tenantId, projectId, id, actor.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/environments/{id}/preview-exchange")
    public ResponseEntity<Void> exchangeTicket(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestParam UUID ticket,
            Principal actor) {
        var result = exchangeService.consumeTicket(tenantId, projectId, id, ticket, actor != null ? actor.getName() : "ticket-bearer");

        var cookie = ResponseCookie.from("kubespawn_preview", result.token())
            .httpOnly(true)
            .sameSite("Strict")
            .path(result.previewPath())
            .maxAge(result.maxAgeSeconds())
            .build();

        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create(result.previewUrl()))
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .build();
    }

    @GetMapping("/environments/{id}/diagnostics")
    public PreviewDiagnosticsService.DiagnosticsDto getDiagnostics(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);
        return diagnosticsService.getDiagnostics(tenantId, projectId, id);
    }

    @GetMapping("/provisioning-progress")
    public List<ProvisioningModels.Progress> getProjectProgress(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);
        return diagnosticsService.getProjectProgress(tenantId, projectId);
    }
}
