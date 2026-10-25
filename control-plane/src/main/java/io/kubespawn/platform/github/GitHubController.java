package io.kubespawn.platform.github;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/github")
public class GitHubController {
    private final GitHubCoordinator coordinator;
    private final GitHubStore store;
    public GitHubController(GitHubCoordinator coordinator,GitHubStore store) { this.coordinator=coordinator; this.store=store; }
    @GetMapping("/status") public Map<String,Object> status() { return Map.of("configured",coordinator.configured(),"repositories",store.repositories()); }
    @PostMapping("/repositories") public BuildModels.Repository enroll(@Valid @RequestBody BuildModels.Enrollment body,Principal actor) {
        return coordinator.enroll(body,actor.getName());
    }
    @GetMapping("/environment-builds") public List<BuildModels.Progress> progress(Principal actor) { return store.progress(actor.getName(),coordinator.configured()); }
}
