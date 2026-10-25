package io.kubespawn.platform.provisioning;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProvisioningSettings {
    public final boolean enabled;
    public final Path kubeconfig;
    public final Path chart;
    public final String server;
    public final int slots;
    public final String previewPublicBase;
    public final String previewInternalBase;
    public final String gatewayNamespace;
    public final String gatewayName;
    public ProvisioningSettings(@Value("${kubespawn.kubernetes.enabled:false}") boolean enabled,
            @Value("${kubespawn.kubernetes.kubeconfig:}") String kubeconfig,
            @Value("${kubespawn.kubernetes.chart:../templates/preview-small}") String chart,
            @Value("${kubespawn.kubernetes.server:}") String server,
            @Value("${kubespawn.kubernetes.slots:2}") int slots,
            @Value("${kubespawn.kubernetes.preview-public-base:http://localhost:9080}") String previewPublicBase,
            @Value("${kubespawn.kubernetes.preview-internal-base:http://preview-edge:8080}") String previewInternalBase,
            @Value("${kubespawn.kubernetes.gateway-namespace:gateway-system}") String gatewayNamespace,
            @Value("${kubespawn.kubernetes.gateway-name:preview-gateway}") String gatewayName) {
        if (enabled && (kubeconfig.isBlank() || slots<1 || slots>10)) throw new IllegalArgumentException("Invalid local Kubernetes configuration.");
        this.enabled=enabled; this.kubeconfig=Path.of(kubeconfig); this.chart=Path.of(chart);
        this.server=server; this.slots=slots;
        this.previewPublicBase=withoutTrailingSlash(previewPublicBase);
        this.previewInternalBase=withoutTrailingSlash(previewInternalBase);
        this.gatewayNamespace=gatewayNamespace; this.gatewayName=gatewayName;
    }
    public String previewPath(UUID environment) { return "/e/"+environment+"/"; }
    public String previewUrl(UUID environment) { return previewPublicBase+previewPath(environment); }
    public String previewInternalUrl(UUID environment) { return previewInternalBase+previewPath(environment); }
    private static String withoutTrailingSlash(String value) { return value.endsWith("/") ? value.substring(0,value.length()-1) : value; }
}
