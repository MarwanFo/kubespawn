package io.kubespawn.platform.provisioning;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/kubernetes")
public class ProvisioningController {
    private final ProvisioningStore store;
    private final ProvisioningSettings settings;
    public ProvisioningController(ProvisioningStore store,ProvisioningSettings settings) { this.store=store; this.settings=settings; }
    @GetMapping("/status") public Map<String,Object> status() { return Map.of("enabled",settings.enabled,"capacitySlots",settings.slots,"previewRoutingReady",false); }
    @GetMapping("/environment-progress") public List<ProvisioningModels.Progress> progress(Principal actor) { return store.progress(actor.getName()); }
}
