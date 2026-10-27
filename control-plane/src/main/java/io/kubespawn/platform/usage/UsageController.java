package io.kubespawn.platform.usage;

import io.kubespawn.platform.usage.UsageModels.*;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UsageController {

    private final UsageService usageService;
    private final PlanService planService;

    @Autowired
    public UsageController(UsageService usageService, PlanService planService) {
        this.usageService = usageService;
        this.planService = planService;
    }

    @GetMapping("/tenants/{tenantId}/usage/summary")
    public UsageSummaryDto getUsageSummary(@PathVariable UUID tenantId, Principal principal) {
        return usageService.getUsageSummary(tenantId, principal);
    }

    @GetMapping("/tenants/{tenantId}/projects/{projectId}/usage")
    public ProjectUsageSummaryDto getProjectUsage(@PathVariable UUID tenantId,
                                                  @PathVariable UUID projectId,
                                                  Principal principal) {
        return usageService.getProjectUsage(tenantId, projectId, principal);
    }

    @GetMapping("/tenants/{tenantId}/usage/history")
    public UsageIntervalsPageDto listUsageHistory(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        return usageService.listUsageIntervals(tenantId, projectId, from, to, page, size, principal);
    }

    @GetMapping("/tenants/{tenantId}/usage/export")
    public ResponseEntity<String> exportUsageCsv(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Principal principal) {
        String csv = usageService.exportUsageCsv(tenantId, from, to, principal);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"usage-export-" + tenantId + ".csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv);
    }

    @GetMapping("/plans")
    public List<PlanDto> listPlans() {
        return planService.listAvailablePlans();
    }

    @GetMapping("/tenants/{tenantId}/plan")
    public TenantSubscriptionDto getTenantPlan(@PathVariable UUID tenantId, Principal principal) {
        return planService.getTenantPlan(tenantId, principal);
    }

    @PutMapping("/tenants/{tenantId}/plan")
    public TenantSubscriptionDto changePlan(@PathVariable UUID tenantId,
                                            @RequestBody ChangePlanRequest request,
                                            Principal principal) {
        return planService.changePlan(tenantId, request, principal);
    }

    @PostMapping("/billing/events")
    public BillingEventDto handleBillingEvent(@RequestBody BillingEventRequest request) {
        return planService.processBillingEvent(request);
    }
}
