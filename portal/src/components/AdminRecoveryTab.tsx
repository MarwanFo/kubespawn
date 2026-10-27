import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle, RefreshCw, CheckCircle2, Wrench
} from 'lucide-react';
import {
  getFailedJobs, executeRecoveryOperation, getProjects,
  type FailedJob, type RecoveryRequestPayload, type RecoveryOperation
} from '../api';

export function AdminRecoveryTab({
  tenantId,
  preselectedEnvId,
  onClearPreselected,
}: {
  tenantId: string;
  preselectedEnvId: string | null;
  onClearPreselected: () => void;
}) {
  const queryClient = useQueryClient();
  const [activeJob, setActiveJob] = useState<FailedJob | null>(null);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [recoverySuccess, setRecoverySuccess] = useState<RecoveryOperation | null>(null);

  const failedJobsQuery = useQuery({
    queryKey: ['failed-jobs', tenantId],
    queryFn: () => getFailedJobs(tenantId),
  });

  useEffect(() => {
    if (preselectedEnvId && failedJobsQuery.data) {
      const match = failedJobsQuery.data.find(j => j.environmentId === preselectedEnvId);
      if (match) {
        setActiveJob(match);
      }
    }
  }, [preselectedEnvId, failedJobsQuery.data]);

  const projectsQuery = useQuery({
    queryKey: ['projects', tenantId],
    queryFn: () => getProjects(tenantId),
  });

  const recoveryMutation = useMutation({
    mutationFn: ({
      projectId,
      payload,
    }: {
      projectId: string;
      payload: RecoveryRequestPayload;
    }) => executeRecoveryOperation(tenantId, projectId, payload),
    onSuccess: (data) => {
      setActiveJob(null);
      setRecoveryError(null);
      setRecoverySuccess(data);
      onClearPreselected();
      void queryClient.invalidateQueries({ queryKey: ['failed-jobs', tenantId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-inventory', tenantId] });
      void queryClient.invalidateQueries({ queryKey: ['tenant-quotas', tenantId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-audit', tenantId] });
    },
    onError: (err: any) => {
      setRecoveryError(err?.message || 'Failed to execute recovery operation.');
    },
  });

  const defaultProjectId = projectsQuery.data && projectsQuery.data[0]?.id
    ? projectsQuery.data[0].id
    : '10000000-0000-0000-0000-000000000011';


  return (
    <div className="admin-recovery-tab">
      {recoverySuccess && (
        <div className="status-banner status-ready" style={{ marginBottom: '1.25rem' }}>
          <CheckCircle2 size={18} />
          <div>
            <strong>Recovery Operation Executed ({recoverySuccess.operationType}):</strong>
            <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.85rem' }}>{recoverySuccess.outcomeDetails}</p>
          </div>
        </div>
      )}

      {recoveryError && (
        <div className="error-banner" style={{ marginBottom: '1.25rem' }}>
          <AlertTriangle size={18} /> {recoveryError}
        </div>
      )}

      <section className="settings-card">
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <Wrench size={22} className="accent-icon" />
            <div>
              <h2>Failed Operations &amp; Approved Recovery</h2>
              <p>Execute authorized operational interventions on failed jobs, stalled reconciliations, or degraded preview environments.</p>
            </div>
          </div>
          <button
            className="ghost-button"
            onClick={() => void failedJobsQuery.refetch()}
            disabled={failedJobsQuery.isFetching}
            style={{ fontSize: '0.85rem' }}
          >
            <RefreshCw size={14} className={failedJobsQuery.isFetching ? 'spin' : ''} /> Refresh
          </button>
        </div>

        {failedJobsQuery.isPending ? (
          <p className="muted">Loading failed jobs…</p>
        ) : failedJobsQuery.error ? (
          <p className="inline-error">{failedJobsQuery.error.message}</p>
        ) : failedJobsQuery.data.length === 0 ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: '#10b981' }}>
            <CheckCircle2 size={32} style={{ margin: '0 auto 0.5rem auto', display: 'block' }} />
            <strong>All preview jobs healthy</strong>
            <p className="muted" style={{ margin: '0.25rem 0 0 0', fontSize: '0.85rem' }}>
              No failed reconciliation jobs or blocked preview workers in this organization.
            </p>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', marginTop: '1rem' }}>
            {failedJobsQuery.data.map((job) => (
              <div
                key={job.id}
                style={{
                  background: 'rgba(239, 68, 68, 0.04)',
                  border: '1px solid rgba(239, 68, 68, 0.3)',
                  borderRadius: '8px',
                  padding: '1.25rem',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '0.75rem' }}>
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <strong style={{ color: '#f8fafc', fontSize: '1rem' }}>{job.repositoryName}</strong>
                      <span className="badge" style={{ background: 'rgba(239, 68, 68, 0.2)', color: '#ef4444', border: '1px solid rgba(239, 68, 68, 0.3)' }}>
                        {job.jobType}
                      </span>
                      <span className="badge" style={{ background: '#1e293b', color: '#94a3b8' }}>
                        Intent v{job.intentVersion}
                      </span>
                      <span className="badge" style={{ background: '#1e293b', color: '#f59e0b' }}>
                        {job.failureCount} Failures ({job.attempts} attempts)
                      </span>
                    </div>
                    <div style={{ color: '#94a3b8', fontSize: '0.8rem', marginTop: '0.4rem' }}>
                      Environment ID: <code style={{ color: '#38bdf8' }}>{job.environmentId}</code>
                    </div>
                  </div>

                  <button
                    className="spawn-button"
                    style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
                    onClick={() => {
                      setRecoveryError(null);
                      setActiveJob(job);
                    }}
                  >
                    <Wrench size={15} /> Recover Environment
                  </button>
                </div>

                {job.lastError && (
                  <div style={{ marginTop: '0.75rem', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(239, 68, 68, 0.2)', borderRadius: '6px', padding: '0.6rem 0.8rem', fontSize: '0.8rem', color: '#fca5a5' }}>
                    <strong>Root Cause:</strong> {job.lastError}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Recovery Modal */}
      {activeJob && (
        <RecoveryModal
          job={activeJob}
          projects={projectsQuery.data || []}
          defaultProjectId={defaultProjectId}
          isPending={recoveryMutation.isPending}
          error={recoveryError}
          onClose={() => setActiveJob(null)}
          onExecute={(projectId, payload) => recoveryMutation.mutate({ projectId, payload })}
        />
      )}
    </div>
  );
}

function RecoveryModal({
  job,
  projects,
  defaultProjectId,
  isPending,
  error,
  onClose,
  onExecute,
}: {
  job: FailedJob;
  projects: any[];
  defaultProjectId: string;
  isPending: boolean;
  error: string | null;
  onClose: () => void;
  onExecute: (projectId: string, payload: RecoveryRequestPayload) => void;
}) {
  const [operationType, setOperationType] = useState<'RETRY_OPERATION' | 'RECONCILE_ENVIRONMENT' | 'FORCE_CLEANUP'>('RETRY_OPERATION');
  const [reason, setReason] = useState('');
  const [selectedProjectId, setSelectedProjectId] = useState<string>(
    defaultProjectId || (projects && projects[0] ? projects[0].id : '')
  );

  useEffect(() => {
    if ((!selectedProjectId || selectedProjectId === '') && projects && projects.length > 0) {
      setSelectedProjectId(projects[0].id);
    }
  }, [projects, selectedProjectId]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!reason.trim()) return;
    const effectiveProjectId = selectedProjectId || defaultProjectId || (projects && projects[0] ? projects[0].id : '');
    if (!effectiveProjectId) return;
    onExecute(effectiveProjectId, {
      operationType,
      reason: reason.trim(),
      environmentId: job.environmentId,
    });
  };


  return (
    <div className="modal-backdrop" onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ background: '#0f172a', border: '1px solid #334155', borderRadius: '12px', padding: '1.75rem', width: '100%', maxWidth: '540px' }}>
        <h3 style={{ margin: '0 0 0.5rem 0', fontSize: '1.25rem', color: '#f8fafc' }}>
          Approved Recovery Operation
        </h3>
        <p className="muted" style={{ margin: '0 0 1.25rem 0', fontSize: '0.85rem' }}>
          All recovery actions are logged to the immutable audit trail with actor identity and mandatory audit reason.
        </p>

        {error && (
          <div className="error-banner" style={{ marginBottom: '1rem' }}>
            <AlertTriangle size={16} /> {error}
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {projects.length > 1 && (
            <label>
              Project Scope
              <select
                value={selectedProjectId}
                onChange={(e) => setSelectedProjectId(e.target.value)}
                required
              >
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>{p.name} ({p.projectKey})</option>
                ))}
              </select>
            </label>
          )}

          <div>
            <label style={{ display: 'block', marginBottom: '0.5rem', fontWeight: 600, color: '#f8fafc' }}>
              Select Operation Action
            </label>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
              <label
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '0.75rem',
                  padding: '0.75rem',
                  borderRadius: '6px',
                  background: operationType === 'RETRY_OPERATION' ? 'rgba(56, 189, 248, 0.1)' : 'rgba(255, 255, 255, 0.02)',
                  border: `1px solid ${operationType === 'RETRY_OPERATION' ? '#38bdf8' : 'rgba(255, 255, 255, 0.08)'}`,
                  cursor: 'pointer',
                }}
              >
                <input
                  type="radio"
                  name="operationType"
                  value="RETRY_OPERATION"
                  checked={operationType === 'RETRY_OPERATION'}
                  onChange={() => setOperationType('RETRY_OPERATION')}
                  style={{ marginTop: '0.2rem' }}
                />
                <div>
                  <strong style={{ color: '#f8fafc' }}>Retry Failed Job (RETRY_OPERATION)</strong>
                  <p className="muted" style={{ margin: '0.2rem 0 0 0', fontSize: '0.8rem' }}>
                    Resets attempt counters and marks failed job AVAILABLE for immediate retry by the worker engine.
                  </p>
                </div>
              </label>

              <label
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '0.75rem',
                  padding: '0.75rem',
                  borderRadius: '6px',
                  background: operationType === 'RECONCILE_ENVIRONMENT' ? 'rgba(56, 189, 248, 0.1)' : 'rgba(255, 255, 255, 0.02)',
                  border: `1px solid ${operationType === 'RECONCILE_ENVIRONMENT' ? '#38bdf8' : 'rgba(255, 255, 255, 0.08)'}`,
                  cursor: 'pointer',
                }}
              >
                <input
                  type="radio"
                  name="operationType"
                  value="RECONCILE_ENVIRONMENT"
                  checked={operationType === 'RECONCILE_ENVIRONMENT'}
                  onChange={() => setOperationType('RECONCILE_ENVIRONMENT')}
                  style={{ marginTop: '0.2rem' }}
                />
                <div>
                  <strong style={{ color: '#f8fafc' }}>Reconcile Environment (RECONCILE_ENVIRONMENT)</strong>
                  <p className="muted" style={{ margin: '0.2rem 0 0 0', fontSize: '0.8rem' }}>
                    Advances the environment intent version, resets phase to WAITING_BUILD, and queues fresh reconciliation.
                  </p>
                </div>
              </label>

              <label
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '0.75rem',
                  padding: '0.75rem',
                  borderRadius: '6px',
                  background: operationType === 'FORCE_CLEANUP' ? 'rgba(239, 68, 68, 0.1)' : 'rgba(255, 255, 255, 0.02)',
                  border: `1px solid ${operationType === 'FORCE_CLEANUP' ? '#ef4444' : 'rgba(255, 255, 255, 0.08)'}`,
                  cursor: 'pointer',
                }}
              >
                <input
                  type="radio"
                  name="operationType"
                  value="FORCE_CLEANUP"
                  checked={operationType === 'FORCE_CLEANUP'}
                  onChange={() => setOperationType('FORCE_CLEANUP')}
                  style={{ marginTop: '0.2rem' }}
                />
                <div>
                  <strong style={{ color: '#ef4444' }}>Force Cleanup (FORCE_CLEANUP)</strong>
                  <p className="muted" style={{ margin: '0.2rem 0 0 0', fontSize: '0.8rem' }}>
                    Marks environment DESTROYED, purges pending jobs, and immediately releases reserved quota capacity. Non-reversible.
                  </p>
                </div>
              </label>
            </div>
          </div>

          <label>
            Mandatory Audit Reason <span style={{ color: '#ef4444' }}>*</span>
            <textarea
              required
              rows={3}
              placeholder="Explain why this administrative intervention is needed (e.g. Argo CD sync timeout after cluster reboot)"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              style={{ width: '100%', marginTop: '0.4rem' }}
            />
          </label>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '0.5rem' }}>
            <button type="button" className="ghost-button" onClick={onClose} disabled={isPending}>
              Cancel
            </button>
            <button
              type="submit"
              className="spawn-button"
              disabled={isPending || !reason.trim()}
              style={{
                background: operationType === 'FORCE_CLEANUP' ? '#ef4444' : undefined,
                borderColor: operationType === 'FORCE_CLEANUP' ? '#ef4444' : undefined,
              }}
            >
              {isPending ? 'Executing…' : 'Execute Recovery'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
