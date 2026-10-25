package io.kubespawn.platform.provisioning;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProvisioningSettings {
    public final boolean enabled;
    public final Path kubeconfig;
    public final Path chart;
    public final String server;
    public final int slots;
    public ProvisioningSettings(@Value("${kubespawn.kubernetes.enabled:false}") boolean enabled,
            @Value("${kubespawn.kubernetes.kubeconfig:}") String kubeconfig,
            @Value("${kubespawn.kubernetes.chart:../templates/preview-small}") String chart,
            @Value("${kubespawn.kubernetes.server:}") String server,
            @Value("${kubespawn.kubernetes.slots:2}") int slots) {
        if (enabled && (kubeconfig.isBlank() || slots<1 || slots>10)) throw new IllegalArgumentException("Invalid local Kubernetes configuration.");
        this.enabled=enabled; this.kubeconfig=Path.of(kubeconfig); this.chart=Path.of(chart);
        this.server=server; this.slots=slots;
    }
}
