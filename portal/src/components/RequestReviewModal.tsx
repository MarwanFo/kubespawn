import { useState } from 'react';
import { useWorkspace } from '../context/WorkspaceContext';
import { createReview } from '../api';
import { CheckSquare, ShieldCheck, X, AlertCircle } from 'lucide-react';

interface RequestReviewModalProps {
  environmentId: string;
  repository: string;
  servingRevision?: string;
  prNumber?: number;
  onClose: () => void;
  onSuccess: () => void;
}

export function RequestReviewModal({
  environmentId,
  repository,
  servingRevision,
  prNumber,
  onClose,
  onSuccess,
}: RequestReviewModalProps) {
  const { activeTenant, activeProject } = useWorkspace();
  const [title, setTitle] = useState(`QA Review for ${repository}${prNumber ? ` (PR #${prNumber})` : ''}`);
  const [prNum, setPrNum] = useState<string>(prNumber ? String(prNumber) : '42');
  const [reviewer, setReviewer] = useState<string>('acme-qa');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeTenant || !activeProject) return;

    setIsSubmitting(true);
    setError(null);

    try {
      await createReview(activeTenant.tenantId, activeProject.id, {
        environmentId,
        title,
        prNumber: prNum ? parseInt(prNum, 10) : undefined,
        reviewerActors: reviewer ? [reviewer.trim()] : undefined,
      });
      onSuccess();
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to request QA review.';
      setError(msg);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal-content review-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title-group">
            <span className="modal-badge"><CheckSquare size={16} /></span>
            <div>
              <h2>Request QA / Reviewer Validation</h2>
              <p className="modal-subtitle">{repository} &middot; Environment <code>{environmentId.slice(0, 8)}</code></p>
            </div>
          </div>
          <button className="icon-button close-btn" onClick={onClose} aria-label="Close modal">
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="modal-body">
          {error && (
            <div className="inline-error" role="alert">
              <AlertCircle size={16} /> {error}
            </div>
          )}

          <div className="form-group">
            <label htmlFor="review-title">Review Title</label>
            <input
              id="review-title"
              type="text"
              required
              value={title}
              onChange={e => setTitle(e.target.value)}
              className="text-input"
            />
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="review-pr">PR Number</label>
              <input
                id="review-pr"
                type="number"
                value={prNum}
                onChange={e => setPrNum(e.target.value)}
                className="text-input"
              />
            </div>

            <div className="form-group">
              <label htmlFor="review-reviewer">Assign QA Reviewer</label>
              <input
                id="review-reviewer"
                type="text"
                required
                placeholder="acme-qa"
                value={reviewer}
                onChange={e => setReviewer(e.target.value)}
                className="text-input"
              />
              <small className="field-hint">Must be an active QA member (self-assignment blocked).</small>
            </div>
          </div>

          <div className="revision-summary-box">
            <ShieldCheck size={16} />
            <div>
              <strong>Target Revision SHA:</strong>
              <code>{servingRevision || 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4'}</code>
              <p className="explanation">Review decisions are cryptographically tied to this revision.</p>
            </div>
          </div>

          <div className="checklist-preview">
            <span className="checklist-preview-title">Automated Verification Checklist (Default):</span>
            <ul>
              <li>Edge routing denies anonymous requests</li>
              <li>Authenticated preview session accesses edge probe route</li>
              <li>Responsive layouts verified on mobile and desktop</li>
              <li>No cross-tenant data visible in state</li>
            </ul>
          </div>

          <div className="modal-actions">
            <button type="button" className="small-button secondary-button" onClick={onClose} disabled={isSubmitting}>
              Cancel
            </button>
            <button type="submit" className="small-button primary-button" disabled={isSubmitting}>
              {isSubmitting ? 'Requesting Review…' : 'Submit Review Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
