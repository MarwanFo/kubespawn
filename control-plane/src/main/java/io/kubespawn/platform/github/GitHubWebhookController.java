package io.kubespawn.platform.github;

import io.kubespawn.platform.lifecycle.LifecycleException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitHubWebhookController {
    private final GitHubWebhookService webhooks;
    public GitHubWebhookController(GitHubWebhookService webhooks) { this.webhooks=webhooks; }

    @PostMapping(path="/integrations/github/webhook",consumes="application/json")
    public ResponseEntity<Map<String,String>> receive(@RequestHeader("X-GitHub-Delivery") UUID delivery,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value="X-Hub-Signature-256",required=false) String signature,HttpServletRequest request) throws IOException {
        final int limit=256*1024;
        if (request.getContentLengthLong()>limit) throw tooLarge();
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if (body.length>limit) throw tooLarge();
        return ResponseEntity.accepted().body(Map.of("state",webhooks.receive(delivery,event,signature,body)));
    }
    private LifecycleException tooLarge() { return new LifecycleException(HttpStatus.PAYLOAD_TOO_LARGE,"WEBHOOK_TOO_LARGE","Webhook exceeds the supported payload limit."); }
}
