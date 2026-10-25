package io.kubespawn.platform.github;

import io.kubespawn.platform.lifecycle.LifecycleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GitHubWebhookService {
    private final GitHubSettings settings;
    private final GitHubGateway github;
    private final GitHubStore store;
    private final ObjectMapper json;
    public GitHubWebhookService(GitHubSettings settings,GitHubGateway github,GitHubStore store,ObjectMapper json) {
        this.settings=settings; this.github=github; this.store=store; this.json=json;
    }

    @Transactional
    public String receive(UUID delivery,String event,String signature,byte[] body) {
        if (!github.configured() || settings.webhookSecret.length()<32) throw new LifecycleException(HttpStatus.SERVICE_UNAVAILABLE,
            "GITHUB_SETUP_REQUIRED","GitHub webhooks are disabled until the App is configured.");
        if (!validSignature(settings.webhookSecret,signature,body)) throw new LifecycleException(HttpStatus.UNAUTHORIZED,
            "INVALID_WEBHOOK_SIGNATURE","Webhook signature is missing or invalid.");
        if (event.length()>64 || !event.matches("[a-z_]+")) throw new LifecycleException(HttpStatus.BAD_REQUEST,"INVALID_EVENT","Invalid webhook event.");
        JsonNode payload;
        try { payload=json.readTree(body); if (payload==null || !payload.isObject()) throw new IllegalArgumentException(); }
        catch (Exception invalid) { throw new LifecycleException(HttpStatus.BAD_REQUEST,"INVALID_WEBHOOK","Webhook JSON is invalid."); }
        long installation=payload.path("installation").path("id").asLong();
        long repository=payload.path("repository").path("id").asLong();
        String action=payload.path("action").asText();
        if (event.equals("installation") && installation>0 && (action.equals("deleted") || action.equals("suspend"))) store.disable(installation,null);
        if (event.equals("installation_repositories") && action.equals("removed") && installation>0) {
            JsonNode removed=payload.path("repositories_removed");
            if (!removed.isArray() || removed.size()>1000) throw new LifecycleException(HttpStatus.BAD_REQUEST,"INVALID_WEBHOOK","Invalid repository removal list.");
            for (JsonNode item:removed) { long id=item.path("id").asLong(); if (id>0) store.disable(installation,id); }
        }
        JsonNode run=payload.path("workflow_run");
        long runId=run.path("id").asLong(); int attempt=run.path("run_attempt").asInt();
        var enrolled=store.repository(repository);
        boolean eligible=event.equals("workflow_run") && action.equals("completed") && runId>0 && attempt>0
            && enrolled.filter(repo -> repo.enabled() && repo.installationId()==installation
                && repo.name().equalsIgnoreCase(payload.path("repository").path("full_name").asText())).isPresent();
        return store.receive(delivery,event,GitHubCoordinator.hash(body),installation>0 ? installation : null,
            repository>0 ? repository : null,runId>0 ? runId : null,attempt>0 ? attempt : null,eligible);
    }

    static boolean validSignature(String secret,String signature,byte[] body) {
        if (signature==null || !signature.matches("sha256=[a-f0-9]{64}")) return false;
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7)));
        } catch (Exception impossible) { throw new IllegalStateException("Webhook HMAC unavailable."); }
    }
}
