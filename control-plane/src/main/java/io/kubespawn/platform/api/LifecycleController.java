package io.kubespawn.platform.api;

import io.kubespawn.platform.lifecycle.CreateEnvironmentRequest;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.lifecycle.LifecycleService;
import io.kubespawn.platform.worker.JobStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1")
public class LifecycleController {
    private final LifecycleService lifecycle;
    private final JobStore jobs;

    public LifecycleController(LifecycleService lifecycle, JobStore jobs) { this.lifecycle = lifecycle; this.jobs = jobs; }

    @PostMapping("/environments")
    public ResponseEntity<LifecycleService.Mutation> create(@Valid @RequestBody CreateEnvironmentRequest body,
            @RequestHeader("Idempotency-Key") UUID key, Principal actor, HttpServletRequest request) {
        var result = lifecycle.create(body, key, actor.getName(), requestId(request));
        return accepted(result).location(URI.create("/api/v1/environments/" + result.environment().id())).body(result);
    }

    @GetMapping("/environments/{id}")
    public ResponseEntity<EnvironmentSummary> get(@PathVariable UUID id, Principal actor) {
        var result = lifecycle.get(id, actor.getName());
        return ResponseEntity.ok().eTag(Long.toString(result.intentVersion())).body(result);
    }

    @PatchMapping("/environments/{id}/expiry")
    public ResponseEntity<LifecycleService.Mutation> extend(@PathVariable UUID id, @Valid @RequestBody ExpiryRequest body,
            @RequestHeader(value = "If-Match", required = false) String match, Principal actor, HttpServletRequest request) {
        var result = lifecycle.extend(id, version(match), body.expiresAt(), actor.getName(), requestId(request));
        return accepted(result).body(result);
    }

    @DeleteMapping("/environments/{id}")
    public ResponseEntity<LifecycleService.Mutation> delete(@PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String match, Principal actor, HttpServletRequest request) {
        var result = lifecycle.delete(id, version(match), actor.getName(), requestId(request));
        return accepted(result).body(result);
    }

    @GetMapping("/environments/{id}/audit")
    public List<LifecycleService.AuditEvent> audit(@PathVariable UUID id, Principal actor) {
        return lifecycle.audit(id, actor.getName());
    }

    @GetMapping("/operations/{id}")
    public JobStore.Operation operation(@PathVariable UUID id, Principal actor) {
        return lifecycle.operation(id, actor.getName());
    }

    @GetMapping("/operations")
    public List<JobStore.Operation> operations(Principal actor) { return jobs.latestOperations(actor.getName()); }

    @PostMapping("/operations/{id}/retry")
    public ResponseEntity<JobStore.Operation> retry(@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match,Principal actor,HttpServletRequest request) {
        return ResponseEntity.accepted().body(jobs.retry(id,actor.getName(),version(match),requestId(request)));
    }

    public record ExpiryRequest(@NotNull Instant expiresAt) {}

    private ResponseEntity.BodyBuilder accepted(LifecycleService.Mutation result) {
        return ResponseEntity.accepted().eTag(Long.toString(result.environment().intentVersion()));
    }

    private long version(String match) {
        if (match == null) {
            throw new LifecycleException(HttpStatus.PRECONDITION_REQUIRED, "VERSION_REQUIRED", "Send the current quoted intent version in If-Match.");
        }
        try {
            if (!match.matches("\"[0-9]+\"")) { throw new NumberFormatException(); }
            return Long.parseLong(match.substring(1, match.length() - 1));
        } catch (NumberFormatException invalid) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_VERSION", "If-Match must contain one quoted numeric intent version.");
        }
    }

    private String requestId(HttpServletRequest request) { return (String) request.getAttribute("requestId"); }
}
