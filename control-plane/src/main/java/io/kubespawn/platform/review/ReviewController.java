package io.kubespawn.platform.review;

import io.kubespawn.platform.review.ReviewModels.AddCommentRequest;
import io.kubespawn.platform.review.ReviewModels.CreateReviewRequest;
import io.kubespawn.platform.review.ReviewModels.ReviewChecklistItemDto;
import io.kubespawn.platform.review.ReviewModels.ReviewCommentDto;
import io.kubespawn.platform.review.ReviewModels.ReviewDecisionDto;
import io.kubespawn.platform.review.ReviewModels.ReviewRequestDto;
import io.kubespawn.platform.review.ReviewModels.SubmitDecisionRequest;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/projects/{projectId}")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/qa-inbox")
    public List<ReviewRequestDto> getInbox(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status,
            Principal actor) {
        return reviewService.listReviews(tenantId, projectId, actor, status);
    }

    @PostMapping("/reviews")
    public ResponseEntity<ReviewRequestDto> createReview(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @RequestBody CreateReviewRequest request,
            Principal actor) {
        var created = reviewService.createReviewRequest(tenantId, projectId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/reviews/{id}")
    public ReviewRequestDto getReview(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            Principal actor) {
        return reviewService.getReview(tenantId, projectId, id, actor);
    }

    @PatchMapping("/reviews/{id}/checklist/{itemKey}")
    public ReviewChecklistItemDto updateChecklist(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @PathVariable String itemKey,
            @RequestBody ReviewModels.UpdateChecklistRequest request,
            Principal actor) {
        return reviewService.updateChecklistItem(tenantId, projectId, id, itemKey, request.passed(), actor);
    }

    @PostMapping("/reviews/{id}/comments")
    public ResponseEntity<ReviewCommentDto> addComment(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody AddCommentRequest request,
            Principal actor) {
        var comment = reviewService.addComment(tenantId, projectId, id, request.content(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(comment);
    }

    @PostMapping("/reviews/{id}/decision")
    public ResponseEntity<ReviewDecisionDto> submitDecision(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody SubmitDecisionRequest request,
            Principal actor) {
        var decision = reviewService.submitDecision(tenantId, projectId, id, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(decision);
    }
}
