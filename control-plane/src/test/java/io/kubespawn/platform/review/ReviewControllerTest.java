package io.kubespawn.platform.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.kubespawn.platform.api.ApiErrors;
import io.kubespawn.platform.config.SecurityConfig;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.review.ReviewModels.ReviewChecklistItemDto;
import io.kubespawn.platform.review.ReviewModels.ReviewCommentDto;
import io.kubespawn.platform.review.ReviewModels.ReviewDecisionDto;
import io.kubespawn.platform.review.ReviewModels.ReviewRequestDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReviewController.class,
    properties = "KUBESPAWN_DEV_TOKEN=test-local-token-with-at-least-32-characters")
@Import({SecurityConfig.class, ApiErrors.class})
@ActiveProfiles("local")
class ReviewControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ReviewService reviewService;

    private static final String TOKEN = "Bearer test-local-token-with-at-least-32-characters";
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID projectId = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private final UUID reviewId = UUID.fromString("10000000-0000-0000-0000-000000000041");
    private final UUID envId = UUID.fromString("10000000-0000-0000-0000-000000000031");

    @Test
    void reviewerCanQueryQAInbox() throws Exception {
        var review = new ReviewRequestDto(
            reviewId, tenantId, projectId, envId, "acme-dev",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4", 42,
            "PR #42: Add shopping cart badge", "PENDING",
            Instant.now(), Instant.now(), "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4",
            false, List.of("acme-qa"), List.of(), List.of(), List.of()
        );

        when(reviewService.listReviews(eq(tenantId), eq(projectId), any(), any()))
            .thenReturn(List.of(review));

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/qa-inbox")
                .header("Authorization", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(reviewId.toString()))
            .andExpect(jsonPath("$[0].status").value("PENDING"))
            .andExpect(jsonPath("$[0].prNumber").value(42));
    }

    @Test
    void developerCanCreateReviewRequest() throws Exception {
        var created = new ReviewRequestDto(
            reviewId, tenantId, projectId, envId, "acme-dev",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4", 42,
            "PR #42: Feature review", "PENDING",
            Instant.now(), Instant.now(), "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4",
            false, List.of("acme-qa"), List.of(), List.of(), List.of()
        );

        when(reviewService.createReviewRequest(eq(tenantId), eq(projectId), any(), any()))
            .thenReturn(created);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "environmentId": "10000000-0000-0000-0000-000000000031",
                      "title": "PR #42: Feature review",
                      "prNumber": 42,
                      "reviewerActors": ["acme-qa"],
                      "checklistItems": ["Edge routing", "Responsive layout"]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(reviewId.toString()))
            .andExpect(jsonPath("$.title").value("PR #42: Feature review"));
    }

    @Test
    void reviewerCanToggleChecklistItem() throws Exception {
        var item = new ReviewChecklistItemDto(
            UUID.randomUUID(), "anonymous-denial", "Edge routing denies anonymous requests",
            true, "acme-qa", Instant.now()
        );

        when(reviewService.updateChecklistItem(eq(tenantId), eq(projectId), eq(reviewId), eq("anonymous-denial"), eq(true), any()))
            .thenReturn(item);

        mvc.perform(patch("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews/" + reviewId + "/checklist/anonymous-denial")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"passed": true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.itemKey").value("anonymous-denial"));
    }

    @Test
    void reviewerCanAddComment() throws Exception {
        var comment = new ReviewCommentDto(
            UUID.randomUUID(), "acme-qa", "QA_REVIEWER", "Edge probe passed 200 OK.", Instant.now()
        );

        when(reviewService.addComment(eq(tenantId), eq(projectId), eq(reviewId), eq("Edge probe passed 200 OK."), any()))
            .thenReturn(comment);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews/" + reviewId + "/comments")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content": "Edge probe passed 200 OK."}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.authorActor").value("acme-qa"))
            .andExpect(jsonPath("$.content").value("Edge probe passed 200 OK."));
    }

    @Test
    void reviewerCanSubmitDecisionApproved() throws Exception {
        var decision = new ReviewDecisionDto(
            UUID.randomUUID(), "APPROVED", "acme-qa", "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4", "All checklists verified.", Instant.now()
        );

        when(reviewService.submitDecision(eq(tenantId), eq(projectId), eq(reviewId), any(), any()))
            .thenReturn(decision);

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews/" + reviewId + "/decision")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "APPROVED",
                      "reviewedRevision": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4",
                      "notes": "All checklists verified."
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("APPROVED"))
            .andExpect(jsonPath("$.reviewedRevision").value("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"));
    }

    @Test
    void selfApprovalIsRejectedWithForbidden() throws Exception {
        when(reviewService.submitDecision(eq(tenantId), eq(projectId), eq(reviewId), any(), any()))
            .thenThrow(new LifecycleException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                "Requesters cannot submit review decisions for their own preview environments."));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews/" + reviewId + "/decision")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "APPROVED",
                      "reviewedRevision": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4",
                      "notes": "Self-approving my own code."
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void staleRevisionIsRejectedWithConflict() throws Exception {
        when(reviewService.submitDecision(eq(tenantId), eq(projectId), eq(reviewId), any(), any()))
            .thenThrow(new LifecycleException(HttpStatus.CONFLICT, "STALE_REVISION",
                "The environment revision has changed since this review was requested."));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/projects/" + projectId + "/reviews/" + reviewId + "/decision")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision": "APPROVED",
                      "reviewedRevision": "old-revision-sha",
                      "notes": "Approving stale revision."
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("STALE_REVISION"));
    }
}
