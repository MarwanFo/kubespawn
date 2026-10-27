import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useWorkspace } from '../context/WorkspaceContext';
import {
  fetchReviews,
  updateChecklistItem,
  addReviewComment,
  submitReviewDecision,
  createPreviewTicket,
} from '../api';
import type { ReviewRequest } from '../api';
import {
  CheckCircle2,
  XCircle,
  ExternalLink,
  GitPullRequest,
  GitBranch,
  ShieldCheck,
  CheckSquare,
  MessageSquare,
  AlertTriangle,
  Send,
  Clock,
  User,
  Filter,
} from 'lucide-react';

export function ReviewInboxView() {
  const { activeTenant, activeProject } = useWorkspace();
  const queryClient = useQueryClient();

  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [commentInputs, setCommentInputs] = useState<Record<string, string>>({});
  const [decisionNotes, setDecisionNotes] = useState<Record<string, string>>({});
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const { data: reviews = [], isLoading, error, refetch } = useQuery<ReviewRequest[]>({
    queryKey: ['reviews', activeTenant?.tenantId, activeProject?.id, statusFilter],
    queryFn: () => fetchReviews(activeTenant!.tenantId, activeProject!.id, statusFilter),
    enabled: !!activeTenant?.tenantId && !!activeProject?.id,
  });

  const checklistMutation = useMutation({
    mutationFn: ({ reviewId, itemKey, passed }: { reviewId: string; itemKey: string; passed: boolean }) =>
      updateChecklistItem(activeTenant!.tenantId, activeProject!.id, reviewId, itemKey, passed),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reviews', activeTenant?.tenantId, activeProject?.id] });
    },
    onError: (err: unknown) => {
      setFeedback({ type: 'error', message: err instanceof Error ? err.message : 'Failed to update checklist item.' });
    },
  });

  const commentMutation = useMutation({
    mutationFn: ({ reviewId, content }: { reviewId: string; content: string }) =>
      addReviewComment(activeTenant!.tenantId, activeProject!.id, reviewId, content),
    onSuccess: (_, variables) => {
      setCommentInputs(prev => ({ ...prev, [variables.reviewId]: '' }));
      queryClient.invalidateQueries({ queryKey: ['reviews', activeTenant?.tenantId, activeProject?.id] });
      setFeedback({ type: 'success', message: 'Note added to review.' });
      setTimeout(() => setFeedback(null), 4000);
    },
    onError: (err: unknown) => {
      setFeedback({ type: 'error', message: err instanceof Error ? err.message : 'Failed to post comment.' });
    },
  });

  const decisionMutation = useMutation({
    mutationFn: ({
      reviewId,
      decision,
      reviewedRevision,
      notes,
    }: {
      reviewId: string;
      decision: 'APPROVED' | 'CHANGES_REQUESTED';
      reviewedRevision: string;
      notes?: string;
    }) =>
      submitReviewDecision(activeTenant!.tenantId, activeProject!.id, reviewId, {
        decision,
        reviewedRevision,
        notes,
      }),
    onSuccess: (_, variables) => {
      setDecisionNotes(prev => ({ ...prev, [variables.reviewId]: '' }));
      queryClient.invalidateQueries({ queryKey: ['reviews', activeTenant?.tenantId, activeProject?.id] });
      const label = variables.decision === 'APPROVED' ? 'APPROVED' : 'CHANGES REQUESTED';
      setFeedback({ type: 'success', message: `Review decision: ${label}. Recorded against commit SHA.` });
      setTimeout(() => setFeedback(null), 5000);
    },
    onError: (err: unknown) => {
      setFeedback({ type: 'error', message: err instanceof Error ? err.message : 'Failed to submit decision.' });
    },
  });

  const handleOpenPreview = async (review: ReviewRequest) => {
    if (!activeTenant || !activeProject) return;
    try {
      const ticketRes = await createPreviewTicket(activeTenant.tenantId, activeProject.id, review.environmentId);
      window.open(ticketRes.exchangeUrl, '_blank', 'noopener,noreferrer');
    } catch (err: unknown) {
      setFeedback({
        type: 'error',
        message: err instanceof Error ? err.message : 'Failed to generate preview ticket for QA reviewer.',
      });
    }
  };

  const handleToggleChecklist = (reviewId: string, itemKey: string, currentPassed: boolean) => {
    checklistMutation.mutate({ reviewId, itemKey, passed: !currentPassed });
  };

  const handleAddComment = (reviewId: string) => {
    const text = commentInputs[reviewId]?.trim();
    if (!text) return;
    commentMutation.mutate({ reviewId, content: text });
  };

  const handleSubmitDecision = (
    review: ReviewRequest,
    decision: 'APPROVED' | 'CHANGES_REQUESTED'
  ) => {
    const notes = decisionNotes[review.id]?.trim();
    decisionMutation.mutate({
      reviewId: review.id,
      decision,
      reviewedRevision: review.targetRevision,
      notes,
    });
  };

  const filterTabs = [
    { key: 'ALL', label: 'All Reviews' },
    { key: 'PENDING', label: 'Pending QA' },
    { key: 'APPROVED', label: 'Approved' },
    { key: 'CHANGES_REQUESTED', label: 'Changes Requested' },
    { key: 'STALE', label: 'Stale Revisions' },
  ];

  return (
    <div className="review-inbox-view">
      <div className="page-heading">
        <div>
          <p className="eyebrow">QUALITY ASSURANCE & CODE REVIEW</p>
          <h1>Review Inbox</h1>
          <p>
            Verify assigned preview environments for <strong>{activeTenant?.tenantName}</strong> &middot;{' '}
            Project: <strong>{activeProject?.name || 'All Projects'}</strong>
          </p>
        </div>
        <span className="role-pill qa">QA_REVIEWER</span>
      </div>

      {feedback && (
        <div className={feedback.type === 'success' ? 'success-banner' : 'inline-error'} role="alert">
          {feedback.type === 'success' ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
          <span>{feedback.message}</span>
        </div>
      )}

      <div className="review-filters-bar">
        <div className="filter-tabs">
          <Filter size={15} className="filter-icon" />
          {filterTabs.map(tab => (
            <button
              key={tab.key}
              className={`filter-tab ${statusFilter === tab.key ? 'active' : ''}`}
              onClick={() => setStatusFilter(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <button className="small-button secondary-button" onClick={() => refetch()} title="Refresh review requests">
          Refresh Inbox
        </button>
      </div>

      {isLoading && (
        <div className="loading-state">
          <p>Loading review assignments…</p>
        </div>
      )}

      {error && (
        <div className="inline-error" role="alert">
          <AlertTriangle size={16} /> {error instanceof Error ? error.message : 'Failed to load QA reviews.'}
        </div>
      )}

      {!isLoading && reviews.length === 0 && (
        <div className="empty-state">
          <CheckSquare size={32} />
          <h3>No review requests found</h3>
          <p>There are no preview reviews matching the filter "{statusFilter}".</p>
        </div>
      )}

      <div className="review-list">
        {reviews.map(review => {
          const isPending = review.status === 'PENDING';
          const isStale = review.isStale || review.status === 'STALE';

          return (
            <article key={review.id} className={`review-card ${isStale ? 'review-card-stale' : ''}`}>
              <div className="review-card-header">
                <div className="review-title-area">
                  {review.prNumber && (
                    <span className="pr-badge">
                      <GitPullRequest size={14} /> #{review.prNumber}
                    </span>
                  )}
                  <h2>{review.title}</h2>
                </div>
                <div className="status-badges">
                  {isStale && (
                    <span className="stale-warning-badge" title="The environment revision has changed since this review was requested">
                      <AlertTriangle size={13} /> STALE REVISION
                    </span>
                  )}
                  <span className={`status-pill ${review.status.toLowerCase()}`}>
                    {review.status.replace('_', ' ')}
                  </span>
                </div>
              </div>

              <div className="review-meta">
                <span>
                  <User size={13} /> Requested by: <strong>{review.requesterActor}</strong>
                </span>
                <span>
                  <Clock size={13} /> {new Date(review.createdAt).toLocaleString()}
                </span>
                <span>
                  <GitBranch size={13} /> Target SHA: <code title={review.targetRevision}>{review.targetRevision.slice(0, 10)}</code>
                </span>
                {review.assignedReviewers && review.assignedReviewers.length > 0 && (
                  <span className="assigned-reviewers">
                    Assigned: <strong>{review.assignedReviewers.join(', ')}</strong>
                  </span>
                )}
                <button
                  type="button"
                  onClick={() => handleOpenPreview(review)}
                  className="preview-link-btn"
                  title="Open private preview workload with one-time exchange ticket"
                >
                  Open Private Preview <ExternalLink size={13} />
                </button>
              </div>

              {isStale && (
                <div className="stale-alert-banner">
                  <AlertTriangle size={16} />
                  <div>
                    <strong>Deployment Revision Mismatch:</strong>
                    <p>
                      The preview environment is now serving revision <code>{review.environmentServingRevision?.slice(0, 10) || 'updated'}</code>,
                      which differs from the target revision <code>{review.targetRevision.slice(0, 10)}</code> evaluated for this review.
                      Decisions cannot be submitted on stale reviews.
                    </p>
                  </div>
                </div>
              )}

              {/* Validation Checklist */}
              <div className="review-checklist">
                <h3>
                  <CheckSquare size={15} /> QA Validation Checklist
                </h3>
                <div className="checklist-items">
                  {review.checklist && review.checklist.length > 0 ? (
                    review.checklist.map(item => (
                      <label key={item.id || item.itemKey} className="checklist-item">
                        <input
                          type="checkbox"
                          checked={item.passed}
                          disabled={!isPending || isStale || checklistMutation.isPending}
                          onChange={() => handleToggleChecklist(review.id, item.itemKey, item.passed)}
                        />
                        <span className={item.passed ? 'checked-text' : ''}>{item.title}</span>
                        {item.passed && item.updatedBy && (
                          <small className="verified-by">· verified by {item.updatedBy}</small>
                        )}
                      </label>
                    ))
                  ) : (
                    <p className="no-items">No checklist items defined.</p>
                  )}
                </div>
              </div>

              {/* Discussion & Notes Feed */}
              <div className="review-comments-section">
                <h3>
                  <MessageSquare size={14} /> Review Notes & Comments
                </h3>
                {review.comments && review.comments.length > 0 ? (
                  <div className="comments-feed">
                    {review.comments.map(c => (
                      <div key={c.id} className="comment-bubble">
                        <div className="comment-header">
                          <strong>{c.authorActor}</strong>
                          <span className="role-tag">{c.authorRole}</span>
                          <span className="comment-time">{new Date(c.createdAt).toLocaleTimeString()}</span>
                        </div>
                        <p className="comment-content">{c.content}</p>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="no-comments">No notes recorded yet.</p>
                )}

                <div className="comment-input-row">
                  <input
                    type="text"
                    placeholder="Add observation, test notes, or reproduction steps…"
                    value={commentInputs[review.id] || ''}
                    onChange={e => setCommentInputs({ ...commentInputs, [review.id]: e.target.value })}
                    onKeyDown={e => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleAddComment(review.id);
                      }
                    }}
                    className="text-input comment-input"
                  />
                  <button
                    className="small-button secondary-button"
                    disabled={!commentInputs[review.id]?.trim() || commentMutation.isPending}
                    onClick={() => handleAddComment(review.id)}
                  >
                    <Send size={13} /> Add Note
                  </button>
                </div>
              </div>

              {/* Immutable Decision Submission / Decision History */}
              <div className="review-actions-panel">
                <div className="anti-self-approval-note">
                  <ShieldCheck size={14} />
                  <span>
                    Decisions are cryptographically bound to deployment SHA <code>{review.targetRevision.slice(0, 10)}</code>.
                    Requesters cannot self-approve.
                  </span>
                </div>

                {isPending && !isStale && (
                  <div className="decision-form">
                    <div className="form-group">
                      <label htmlFor={`notes-${review.id}`}>Decision Summary & Test Notes</label>
                      <textarea
                        id={`notes-${review.id}`}
                        rows={2}
                        placeholder="State test suite outcomes, edge test results, or reasons for requesting changes…"
                        value={decisionNotes[review.id] || ''}
                        onChange={e => setDecisionNotes({ ...decisionNotes, [review.id]: e.target.value })}
                        className="text-input"
                      />
                    </div>
                    <div className="decision-buttons">
                      <button
                        className="danger-button"
                        disabled={decisionMutation.isPending}
                        onClick={() => handleSubmitDecision(review, 'CHANGES_REQUESTED')}
                      >
                        <XCircle size={15} /> Request Changes
                      </button>
                      <button
                        className="primary-button"
                        disabled={decisionMutation.isPending}
                        onClick={() => handleSubmitDecision(review, 'APPROVED')}
                      >
                        <CheckCircle2 size={15} /> Approve Revision
                      </button>
                    </div>
                  </div>
                )}

                {/* Preserved Past Decisions History */}
                {review.decisions && review.decisions.length > 0 && (
                  <div className="decision-history">
                    <h4>Decision History</h4>
                    <div className="decision-history-list">
                      {review.decisions.map(d => (
                        <div key={d.id} className={`decision-entry ${d.decision.toLowerCase()}`}>
                          <div className="decision-entry-header">
                            <span className={`decision-badge ${d.decision.toLowerCase()}`}>
                              {d.decision === 'APPROVED' ? <CheckCircle2 size={13} /> : <XCircle size={13} />}
                              {d.decision}
                            </span>
                            <span>by <strong>{d.decisionActor}</strong></span>
                            <span>on {new Date(d.createdAt).toLocaleString()}</span>
                            <span>Revision: <code>{d.reviewedRevision.slice(0, 10)}</code></span>
                          </div>
                          {d.notes && <p className="decision-notes">{d.notes}</p>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}
