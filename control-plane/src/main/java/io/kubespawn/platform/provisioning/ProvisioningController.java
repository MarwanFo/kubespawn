package io.kubespawn.platform.provisioning;

import java.security.Principal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/kubernetes")
public class ProvisioningController {
    private final ProvisioningStore store;
    private final ProvisioningSettings settings;
    private final ProvisioningGateway gateway;
    public ProvisioningController(ProvisioningStore store,ProvisioningSettings settings,ProvisioningGateway gateway) {
        this.store=store; this.settings=settings; this.gateway=gateway;
    }
    @GetMapping("/status") public Map<String,Object> status() { return Map.of("enabled",settings.enabled,"capacitySlots",settings.slots,"previewRoutingReady",gateway.previewRoutingReady()); }
    @GetMapping("/environment-progress") public List<ProvisioningModels.Progress> progress(Principal actor) { return store.progress(actor.getName()); }
    @PostMapping("/environments/{id}/preview-session")
    public ResponseEntity<Void> preview(@PathVariable UUID id,Principal actor) {
        var preview=store.preview(id,actor.getName());
        String token=gateway.previewAccessToken(preview.namespace());
        long seconds=Math.max(1,Duration.between(Instant.now(),preview.expiresAt()).toSeconds());
        var cookie=ResponseCookie.from("kubespawn_preview",token).httpOnly(true).sameSite("Strict")
            .path(settings.previewPath(id)).maxAge(seconds).build();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(settings.previewUrl(id)))
            .header(HttpHeaders.SET_COOKIE,cookie.toString()).build();
    }
}
