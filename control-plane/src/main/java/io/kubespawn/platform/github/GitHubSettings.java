package io.kubespawn.platform.github;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitHubSettings {
    final boolean enabled;
    final String appId;
    final Path privateKeyFile;
    final String webhookSecret;

    public GitHubSettings(@Value("${kubespawn.github.enabled:false}") boolean enabled,
            @Value("${kubespawn.github.app-id:}") String appId,
            @Value("${kubespawn.github.private-key-file:}") String privateKeyFile,
            @Value("${kubespawn.github.webhook-secret:}") String webhookSecret) {
        this.enabled=enabled; this.appId=appId;
        this.privateKeyFile=privateKeyFile.isBlank() ? null : Path.of(privateKeyFile);
        this.webhookSecret=webhookSecret;
        if (enabled && (!appId.matches("[0-9]+") || this.privateKeyFile == null || webhookSecret.length()<32)) {
            throw new IllegalArgumentException("Configure GitHub App ID, private-key file, and a strong webhook secret before enabling GitHub.");
        }
    }
}
