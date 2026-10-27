package io.kubespawn.platform.review;

import io.kubespawn.platform.identity.AuthorizationService;
import io.kubespawn.platform.lifecycle.LifecycleException;
import io.kubespawn.platform.review.ReviewModels.AddCommentRequest;
import io.kubespawn.platform.review.ReviewModels.CreateReviewRequest;
import io.kubespawn.platform.review.ReviewModels.ReviewChecklistItemDto;
import io.kubespawn.platform.review.ReviewModels.ReviewCommentDto;
import io.kubespawn.platform.review.ReviewModels.ReviewDecisionDto;
import io.kubespawn.platform.review.ReviewModels.ReviewRequestDto;
import io.kubespawn.platform.review.ReviewModels.SubmitDecisionRequest;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final io.kubespawn.platform.notification.NotificationService notificationService;

    public ReviewService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this(jdbc, authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewService(JdbcTemplate jdbc, AuthorizationService authorization,
                         @org.springframework.beans.factory.annotation.Autowired(required = false) io.kubespawn.platform.notification.NotificationService notificationService) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<ReviewRequestDto> listReviews(UUID tenantId, UUID projectId, Principal actor, String statusFilter) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        StringBuilder sql = new StringBuilder("""
            SELECT r.id, r.tenant_id, r.project_id, r.environment_id,
                   r.requester_actor, r.target_revision, r.pr_number, r.title, r.status,
                   r.created_at, r.updated_at,
                   e.serving_revision as env_serving_revision
            FROM review_requests r
            LEFT JOIN environments e ON e.id = r.environment_id
            WHERE r.tenant_id = ? AND r.project_id = ?
        """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(projectId);

        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            sql.append(" AND r.status = ?");
            params.add(statusFilter.toUpperCase());
        }

        sql.append(" ORDER BY r.created_at DESC");

        List<ReviewRequestDto> results = jdbc.query(sql.toString(), (rs, rowNum) -> mapReviewHeader(rs), params.toArray());

        return results.stream().map(this::hydrateReview).toList();
    }

    @Transactional
    public ReviewRequestDto getReview(UUID tenantId, UUID projectId, UUID reviewId, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        String sql = """
            SELECT r.id, r.tenant_id, r.project_id, r.environment_id,
                   r.requester_actor, r.target_revision, r.pr_number, r.title, r.status,
                   r.created_at, r.updated_at,
                   e.serving_revision as env_serving_revision
            FROM review_requests r
            LEFT JOIN environments e ON e.id = r.environment_id
            WHERE r.id = ? AND r.tenant_id = ? AND r.project_id = ?
        """;

        List<ReviewRequestDto> results = jdbc.query(sql, (rs, rowNum) -> mapReviewHeader(rs), reviewId, tenantId, projectId);
        if (results.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review request not found.");
        }

        ReviewRequestDto review = results.get(0);

        // Optimistic concurrency / Stale revision check:
        // If status is PENDING and the environment's serving revision changed, mark STALE
        if ("PENDING".equals(review.status()) && review.environmentServingRevision() != null 
                && !review.targetRevision().equals(review.environmentServingRevision())) {
            jdbc.update("UPDATE review_requests SET status = 'STALE', updated_at = CURRENT_TIMESTAMP WHERE id = ?", reviewId);
            review = new ReviewRequestDto(
                review.id(), review.tenantId(), review.projectId(), review.environmentId(),
                review.requesterActor(), review.targetRevision(), review.prNumber(), review.title(),
                "STALE", review.createdAt(), Instant.now(), review.environmentServingRevision(),
                true, review.assignedReviewers(), review.checklist(), review.comments(), review.decisions()
            );
        }

        return hydrateReview(review);
    }

    @Transactional
    public ReviewRequestDto createReviewRequest(UUID tenantId, UUID projectId, CreateReviewRequest req, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);
        authorization.requireRole(actor, tenantId, "DEVELOPER", "PORTAL_ADMIN");

        if (req.environmentId() == null) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_ENVIRONMENT", "Environment ID is required.");
        }

        // Fetch environment to review
        String envSql = """
            SELECT e.id, e.serving_revision, e.requested_revision, e.phase, e.repository_name, s.pr_number
            FROM environments e
            LEFT JOIN environment_sources s ON s.id = e.source_id
            WHERE e.id = ? AND e.tenant_id = ? AND e.project_id = ?
        """;

        var envRows = jdbc.queryForList(envSql, req.environmentId(), tenantId, projectId);
        if (envRows.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Preview environment not found in this project.");
        }

        var env = envRows.get(0);
        String phase = (String) env.get("phase");
        if ("DESTROYED".equalsIgnoreCase(phase)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "ENVIRONMENT_DESTROYED", "Cannot request review for a destroyed environment.");
        }

        String servingRev = (String) env.get("serving_revision");
        if (servingRev == null || servingRev.isBlank()) {
            servingRev = (String) env.get("requested_revision");
        }
        if (servingRev == null || servingRev.isBlank()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "REVISION_NOT_AVAILABLE", "Environment has no deployed revision available for review.");
        }

        String actorName = actor.getName();

        // Enforce anti-self-approval rule on reviewer selection:
        // Requesters cannot assign themselves as reviewers
        List<String> reviewers = req.reviewerActors() != null ? req.reviewerActors() : List.of();
        for (String rev : reviewers) {
            if (rev != null && rev.equalsIgnoreCase(actorName)) {
                throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_REVIEWER_ASSIGNMENT",
                    "Requesters cannot assign themselves as reviewers. Conflict of interest rule violated.");
            }
        }

        UUID reviewId = UUID.randomUUID();
        Integer prNumber = req.prNumber() != null ? req.prNumber() : (Integer) env.get("pr_number");
        String title = req.title() != null && !req.title().isBlank() 
            ? req.title() 
            : ("QA Review for " + env.get("repository_name") + (prNumber != null ? " (PR #" + prNumber + ")" : ""));

        // Insert review request
        jdbc.update("""
            INSERT INTO review_requests (
                id, tenant_id, project_id, environment_id, requester_actor,
                target_revision, pr_number, title, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, reviewId, tenantId, projectId, req.environmentId(), actorName, servingRev, prNumber, title);

        // Insert reviewer assignments
        if (reviewers.isEmpty()) {
            // If none provided, assign default acme-qa or leave unassigned
            jdbc.update("""
                INSERT INTO review_assignments (id, review_request_id, reviewer_actor, status, assigned_at)
                VALUES (?, ?, 'acme-qa', 'ASSIGNED', CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), reviewId);
        } else {
            for (String revActor : reviewers) {
                jdbc.update("""
                    INSERT INTO review_assignments (id, review_request_id, reviewer_actor, status, assigned_at)
                    VALUES (?, ?, ?, 'ASSIGNED', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), reviewId, revActor);
            }
        }

        // Insert checklist items
        List<String> checklistTitles = req.checklistItems();
        if (checklistTitles == null || checklistTitles.isEmpty()) {
            checklistTitles = List.of(
                "Edge routing denies anonymous requests",
                "Authenticated preview session accesses edge probe route",
                "Responsive layouts verified on mobile and desktop",
                "No cross-tenant data visible in state"
            );
        }

        for (int i = 0; i < checklistTitles.size(); i++) {
            String itemTitle = checklistTitles.get(i);
            String itemKey = "item-" + (i + 1);
            jdbc.update("""
                INSERT INTO review_test_checklists (id, review_request_id, item_key, title, passed, updated_by, updated_at)
                VALUES (?, ?, ?, ?, false, ?, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), reviewId, itemKey, itemTitle, actorName);
        }

        if (notificationService != null) {
            List<String> targetReviewers = reviewers.isEmpty() ? List.of("acme-qa") : reviewers;
            for (String revActor : targetReviewers) {
                var userIds = jdbc.query(
                    "SELECT u.id FROM app_users u LEFT JOIN user_identities i ON i.user_id = u.id WHERE i.subject = ? OR LOWER(u.email) = LOWER(?) OR u.display_name = ?",
                    (rs, rn) -> (UUID) rs.getObject("id"),
                    revActor, revActor, revActor
                );
                for (UUID revUserId : userIds) {
                    try {
                        notificationService.createNotification(new io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand(
                            tenantId,
                            revUserId,
                            "REVIEW_REQUESTED",
                            "QA Review Requested: " + title,
                            actorName + " requested your review on " + env.get("repository_name") + " (" + servingRev + ").",
                            "REVIEW",
                            reviewId.toString(),
                            "/reviews",
                            "review-request:" + reviewId + ":" + revUserId
                        ));
                    } catch (Exception ignored) {}
                }
            }
        }

        return getReview(tenantId, projectId, reviewId, actor);
    }

    @Transactional
    public ReviewChecklistItemDto updateChecklistItem(UUID tenantId, UUID projectId, UUID reviewId, String itemKey, boolean passed, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        // Ensure review exists in project
        Boolean exists = jdbc.queryForObject(
            "SELECT count(*) > 0 FROM review_requests WHERE id = ? AND tenant_id = ? AND project_id = ?",
            Boolean.class, reviewId, tenantId, projectId
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review request not found.");
        }

        int updated = jdbc.update("""
            UPDATE review_test_checklists
            SET passed = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
            WHERE review_request_id = ? AND item_key = ?
        """, passed, actor.getName(), reviewId, itemKey);

        if (updated == 0) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "CHECKLIST_ITEM_NOT_FOUND", "Checklist item not found.");
        }

        return jdbc.queryForObject("""
            SELECT id, item_key, title, passed, updated_by, updated_at
            FROM review_test_checklists
            WHERE review_request_id = ? AND item_key = ?
        """, (rs, rn) -> new ReviewChecklistItemDto(
            (UUID) rs.getObject("id"),
            rs.getString("item_key"),
            rs.getString("title"),
            rs.getBoolean("passed"),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant()
        ), reviewId, itemKey);
    }

    @Transactional
    public ReviewCommentDto addComment(UUID tenantId, UUID projectId, UUID reviewId, String content, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        if (content == null || content.trim().isEmpty()) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "EMPTY_COMMENT", "Comment content cannot be empty.");
        }

        Boolean exists = jdbc.queryForObject(
            "SELECT count(*) > 0 FROM review_requests WHERE id = ? AND tenant_id = ? AND project_id = ?",
            Boolean.class, reviewId, tenantId, projectId
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review request not found.");
        }

        var permissions = authorization.getEffectivePermissions(actor, tenantId);
        String role = permissions.role();

        UUID commentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO review_comments (id, review_request_id, author_actor, author_role, content, created_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, commentId, reviewId, actor.getName(), role, content.trim());

        return new ReviewCommentDto(commentId, actor.getName(), role, content.trim(), Instant.now());
    }

    @Transactional
    public ReviewDecisionDto submitDecision(UUID tenantId, UUID projectId, UUID reviewId, SubmitDecisionRequest req, Principal actor) {
        authorization.requireProjectAccess(actor, tenantId, projectId);

        if (req.decision() == null || (!req.decision().equals("APPROVED") && !req.decision().equals("CHANGES_REQUESTED"))) {
            throw new LifecycleException(HttpStatus.BAD_REQUEST, "INVALID_DECISION", "Decision must be either 'APPROVED' or 'CHANGES_REQUESTED'.");
        }

        // Fetch review request details
        String sql = """
            SELECT r.id, r.requester_actor, r.target_revision, r.status,
                   e.serving_revision as env_serving_revision
            FROM review_requests r
            LEFT JOIN environments e ON e.id = r.environment_id
            WHERE r.id = ? AND r.tenant_id = ? AND r.project_id = ?
        """;
        var rows = jdbc.queryForList(sql, reviewId, tenantId, projectId);
        if (rows.isEmpty()) {
            throw new LifecycleException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review request not found.");
        }

        var row = rows.get(0);
        String requesterActor = (String) row.get("requester_actor");
        String targetRevision = (String) row.get("target_revision");
        String currentStatus = (String) row.get("status");
        String envServingRev = (String) row.get("env_serving_revision");

        String actorName = actor.getName();

        // 1. Enforce Anti-Self-Approval
        if (requesterActor != null && requesterActor.equalsIgnoreCase(actorName)) {
            throw new LifecycleException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                "Requesters cannot submit review decisions for their own preview environments.");
        }

        // 2. Enforce Assignment for non-admins
        var permissions = authorization.getEffectivePermissions(actor, tenantId);
        if (!"PORTAL_ADMIN".equals(permissions.role())) {
            Boolean assigned = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM review_assignments
                    WHERE review_request_id = ? AND reviewer_actor = ? AND status = 'ASSIGNED'
                )
            """, Boolean.class, reviewId, actorName);
            if (!Boolean.TRUE.equals(assigned)) {
                throw new LifecycleException(HttpStatus.FORBIDDEN, "NOT_ASSIGNED_REVIEWER",
                    "You are not assigned to review this request.");
            }
        }

        // 3. Status check: must be PENDING
        if (!"PENDING".equals(currentStatus)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "REVIEW_NOT_PENDING",
                "Cannot submit decision for review with status: " + currentStatus);
        }

        // 4. Stale Revision & Optimistic Concurrency check
        if (req.reviewedRevision() != null && !req.reviewedRevision().equalsIgnoreCase(targetRevision)) {
            throw new LifecycleException(HttpStatus.CONFLICT, "REVISION_MISMATCH",
                "Submitted revision does not match the review's target revision " + targetRevision);
        }

        // Check if environment serving revision changed in the meantime
        if (envServingRev != null && !envServingRev.equalsIgnoreCase(targetRevision)) {
            jdbc.update("UPDATE review_requests SET status = 'STALE', updated_at = CURRENT_TIMESTAMP WHERE id = ?", reviewId);
            throw new LifecycleException(HttpStatus.CONFLICT, "STALE_REVISION",
                "The environment revision has changed since this review was requested. Please request a new review for the updated revision.");
        }

        // 5. Insert Immutable Decision
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO review_decisions (id, review_request_id, decision, decision_actor, reviewed_revision, notes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, decisionId, reviewId, req.decision(), actorName, targetRevision, req.notes());

        // 6. Update review status
        jdbc.update("UPDATE review_requests SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", req.decision(), reviewId);

        // 7. Update assignment to COMPLETED
        jdbc.update("UPDATE review_assignments SET status = 'COMPLETED' WHERE review_request_id = ? AND reviewer_actor = ?", reviewId, actorName);

        if (notificationService != null) {
            var requesterIds = jdbc.query(
                "SELECT u.id FROM app_users u LEFT JOIN user_identities i ON i.user_id = u.id WHERE i.subject = ? OR LOWER(u.email) = LOWER(?) OR u.display_name = ?",
                (rs, rn) -> (UUID) rs.getObject("id"),
                requesterActor, requesterActor, requesterActor
            );
            for (UUID reqUserId : requesterIds) {
                try {
                    notificationService.createNotification(new io.kubespawn.platform.notification.NotificationModels.CreateNotificationCommand(
                        tenantId,
                        reqUserId,
                        "REVIEW_DECISION",
                        "QA Review " + req.decision() + ": " + row.get("target_revision"),
                        actorName + " submitted decision " + req.decision() + " for revision " + targetRevision + ".",
                        "REVIEW",
                        reviewId.toString(),
                        "/reviews",
                        "review-decision:" + reviewId + ":" + req.decision()
                    ));
                } catch (Exception ignored) {}
            }
        }

        return new ReviewDecisionDto(decisionId, req.decision(), actorName, targetRevision, req.notes(), Instant.now());
    }

    private ReviewRequestDto mapReviewHeader(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        UUID tenantId = (UUID) rs.getObject("tenant_id");
        UUID projectId = (UUID) rs.getObject("project_id");
        UUID envId = (UUID) rs.getObject("environment_id");
        String requesterActor = rs.getString("requester_actor");
        String targetRevision = rs.getString("target_revision");
        Integer prNumber = (Integer) rs.getObject("pr_number");
        String title = rs.getString("title");
        String status = rs.getString("status");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        String envServingRev = rs.getString("env_serving_revision");
        boolean isStale = "STALE".equals(status) || (envServingRev != null && !targetRevision.equals(envServingRev));

        return new ReviewRequestDto(
            id, tenantId, projectId, envId, requesterActor, targetRevision, prNumber, title,
            status, createdAt, updatedAt, envServingRev, isStale,
            List.of(), List.of(), List.of(), List.of()
        );
    }

    private ReviewRequestDto hydrateReview(ReviewRequestDto header) {
        UUID reviewId = header.id();

        List<String> assignedReviewers = jdbc.query(
            "SELECT reviewer_actor FROM review_assignments WHERE review_request_id = ? AND status = 'ASSIGNED'",
            (rs, rn) -> rs.getString("reviewer_actor"),
            reviewId
        );

        List<ReviewChecklistItemDto> checklist = jdbc.query("""
            SELECT id, item_key, title, passed, updated_by, updated_at
            FROM review_test_checklists
            WHERE review_request_id = ?
            ORDER BY item_key ASC
        """, (rs, rn) -> new ReviewChecklistItemDto(
            (UUID) rs.getObject("id"),
            rs.getString("item_key"),
            rs.getString("title"),
            rs.getBoolean("passed"),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant()
        ), reviewId);

        List<ReviewCommentDto> comments = jdbc.query("""
            SELECT id, author_actor, author_role, content, created_at
            FROM review_comments
            WHERE review_request_id = ?
            ORDER BY created_at ASC
        """, (rs, rn) -> new ReviewCommentDto(
            (UUID) rs.getObject("id"),
            rs.getString("author_actor"),
            rs.getString("author_role"),
            rs.getString("content"),
            rs.getTimestamp("created_at").toInstant()
        ), reviewId);

        List<ReviewDecisionDto> decisions = jdbc.query("""
            SELECT id, decision, decision_actor, reviewed_revision, notes, created_at
            FROM review_decisions
            WHERE review_request_id = ?
            ORDER BY created_at DESC
        """, (rs, rn) -> new ReviewDecisionDto(
            (UUID) rs.getObject("id"),
            rs.getString("decision"),
            rs.getString("decision_actor"),
            rs.getString("reviewed_revision"),
            rs.getString("notes"),
            rs.getTimestamp("created_at").toInstant()
        ), reviewId);

        return new ReviewRequestDto(
            header.id(), header.tenantId(), header.projectId(), header.environmentId(),
            header.requesterActor(), header.targetRevision(), header.prNumber(), header.title(),
            header.status(), header.createdAt(), header.updatedAt(), header.environmentServingRevision(),
            header.isStale(), assignedReviewers, checklist, comments, decisions
        );
    }
}
