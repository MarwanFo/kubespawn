package io.kubespawn.platform.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ReviewModels {

    public record ReviewRequestDto(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID environmentId,
        String requesterActor,
        String targetRevision,
        Integer prNumber,
        String title,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String environmentServingRevision,
        boolean isStale,
        List<String> assignedReviewers,
        List<ReviewChecklistItemDto> checklist,
        List<ReviewCommentDto> comments,
        List<ReviewDecisionDto> decisions
    ) {}

    public record ReviewChecklistItemDto(
        UUID id,
        String itemKey,
        String title,
        boolean passed,
        String updatedBy,
        Instant updatedAt
    ) {}

    public record ReviewCommentDto(
        UUID id,
        String authorActor,
        String authorRole,
        String content,
        Instant createdAt
    ) {}

    public record ReviewDecisionDto(
        UUID id,
        String decision,
        String decisionActor,
        String reviewedRevision,
        String notes,
        Instant createdAt
    ) {}

    public record CreateReviewRequest(
        UUID environmentId,
        String title,
        Integer prNumber,
        List<String> reviewerActors,
        List<String> checklistItems
    ) {}

    public record UpdateChecklistRequest(
        boolean passed
    ) {}

    public record AddCommentRequest(
        String content
    ) {}

    public record SubmitDecisionRequest(
        String decision,
        String reviewedRevision,
        String notes
    ) {}
}
