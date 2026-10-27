package io.kubespawn.platform.preview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.api.ApiErrors;
import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.provisioning.ProvisioningModels;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PreviewController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import({SecurityConfig.class, ApiErrors.class})
@ActiveProfiles("local")
class PreviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PreviewAccessPolicy accessPolicy;
    @MockitoBean PreviewExchangeService exchangeService;
    @MockitoBean PreviewDiagnosticsService diagnosticsService;
    @MockitoBean AuthorizationService authorization;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private final UUID envId = UUID.fromString("10000000-0000-0000-0000-000000000031");
    private final UUID ticket = UUID.randomUUID();

    @Test
    void developerCanCreatePreviewTicket() throws Exception {
        var ticketResponse = new PreviewExchangeService.TicketResponse(
            ticket,
            "/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/preview-exchange?ticket=" + ticket,
            "/e/" + envId + "/",
            Instant.now().plusSeconds(60)
        );

        when(exchangeService.createTicket(eq(tenantId), eq(projectId), eq(envId), any()))
            .thenReturn(ticketResponse);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/preview-ticket")
                .header("Authorization", TOKEN))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ticket").value(ticket.toString()))
            .andExpect(jsonPath("$.previewPath").value("/e/" + envId + "/"));

        verify(accessPolicy).requirePreviewAccess(any(), eq(tenantId), eq(projectId), eq(envId));
    }

    @Test
    void exchangeTicketSetsPathScopedCookieAndRedirects() throws Exception {
        var exchangeResult = new PreviewExchangeService.ExchangeResult(
            "secret-preview-token-123",
            "/e/" + envId + "/",
            "http://localhost:9080/e/" + envId + "/",
            3600L
        );

        when(exchangeService.consumeTicket(eq(tenantId), eq(projectId), eq(envId), eq(ticket), any()))
            .thenReturn(exchangeResult);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/preview-exchange")
                .header("Authorization", TOKEN)
                .param("ticket", ticket.toString()))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "http://localhost:9080/e/" + envId + "/"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("kubespawn_preview=secret-preview-token-123")))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/e/" + envId + "/")))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test
    void qaReviewerIsDeniedPreviewAccessByDefault() throws Exception {
        doThrow(new LifecycleException(HttpStatus.FORBIDDEN, "QA_REVIEW_REQUIRED",
            "QA preview access requires an active review assignment. Assignments are managed in QA Reviews."))
            .when(accessPolicy).requirePreviewAccess(any(), eq(tenantId), eq(projectId), eq(envId));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/preview-ticket")
                .header("Authorization", TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("QA_REVIEW_REQUIRED"));
    }

    @Test
    void replayedOrInvalidTicketFails() throws Exception {
        doThrow(new LifecycleException(HttpStatus.UNAUTHORIZED, "INVALID_PREVIEW_TICKET",
            "The preview exchange ticket is invalid, expired, or has already been used."))
            .when(exchangeService).consumeTicket(eq(tenantId), eq(projectId), eq(envId), eq(ticket), any());

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/preview-exchange")
                .header("Authorization", TOKEN)
                .param("ticket", ticket.toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.title").value("INVALID_PREVIEW_TICKET"));
    }

    @Test
    void projectMembersCanAccessDiagnostics() throws Exception {
        var diagnostics = new PreviewDiagnosticsService.DiagnosticsDto(
            envId, tenantId, projectId, "acme/web-frontend", "feature/shopping-cart",
            "ks-preview-42", "READY", "ACTIVE", "sha-a1b2c3d4",
            Instant.now().minusSeconds(3600), Instant.now().plusSeconds(7200), false,
            "http://localhost:9080/e/" + envId + "/", true,
            "READY", "Preview route responding.",
            List.of(
                new PreviewDiagnosticsService.ConditionDto("BuildVerified", "True", "BuildArtifactSigned", "Artifact valid"),
                new PreviewDiagnosticsService.ConditionDto("WorkloadProvisioned", "True", "PodsRunningAndHealthy", "Deployment healthy"),
                new PreviewDiagnosticsService.ConditionDto("RouteProgrammed", "True", "EnvoyHttpRouteAccepted", "Route configured"),
                new PreviewDiagnosticsService.ConditionDto("EdgeProbePassed", "True", "AuthenticatedProbeSuccess", "Probe 200 OK")
            ),
            List.of("2026-10-26T18:00:00Z [acme-dev] ENVIRONMENT_REQUESTED")
        );

        when(diagnosticsService.getDiagnostics(eq(tenantId), eq(projectId), eq(envId)))
            .thenReturn(diagnostics);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/environments/" + envId + "/diagnostics")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environmentId").value(envId.toString()))
            .andExpect(jsonPath("$.phase").value("READY"))
            .andExpect(jsonPath("$.previewRoutingReady").value(true))
            .andExpect(jsonPath("$.conditions[0].type").value("BuildVerified"))
            .andExpect(jsonPath("$.conditions[3].type").value("EdgeProbePassed"));

        verify(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));
    }

    @Test
    void projectScopedProvisioningProgressReturnsProgressList() throws Exception {
        var progressList = List.of(
            new ProvisioningModels.Progress(
                envId, "READY", "Route configured", "ks-preview-42", "ks-" + envId.toString().replace("-", ""),
                false, Instant.now().toString(), "http://localhost:9080/e/" + envId + "/"
            )
        );

        when(diagnosticsService.getProjectProgress(eq(tenantId), eq(projectId)))
            .thenReturn(progressList);

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/provisioning-progress")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].environmentId").value(envId.toString()))
            .andExpect(jsonPath("$[0].stage").value("READY"));

        verify(authorization).requireProjectAccess(any(), eq(tenantId), eq(projectId));
    }
}
