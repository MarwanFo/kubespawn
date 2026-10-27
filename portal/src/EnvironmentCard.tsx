import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowUpRight, Box, Clock3, History, Trash2, Activity, CheckSquare } from 'lucide-react';
import { deleteEnvironment, extendEnvironment, getJson, retryOperation, createPreviewTicket } from './api';
import type { AuditEvent, BuildProgress, Environment, Operation, ProvisioningProgress } from './api';
import { useWorkspace } from './context/WorkspaceContext';
import { DiagnosticsModal } from './components/DiagnosticsModal';
import { RequestReviewModal } from './components/RequestReviewModal';

export function EnvironmentCard({ environment, canManage, operation, trackProgress, build, provisioning }: {
  environment: Environment; canManage: boolean; operation?: Operation; trackProgress: boolean; build?: BuildProgress; provisioning?: ProvisioningProgress;
}) {
  const client = useQueryClient();
  const { activeTenant, activeProject } = useWorkspace();
  const [showAudit, setShowAudit] = useState(false);
  const [showDiagnostics, setShowDiagnostics] = useState(false);
  const [showReviewModal, setShowReviewModal] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [ticketError, setTicketError] = useState('');

  const audit = useQuery({
    queryKey: ['audit', environment.id], queryFn: () => getJson<AuditEvent[]>(`/api/v1/environments/${environment.id}/audit`),
    enabled: showAudit,
  });

  const action = useMutation({
    mutationFn: async (type: 'extend' | 'delete' | 'retry') => {
      if (type === 'retry') {
        if (!operation) throw new Error('Refresh operation progress before retrying.');
        const result = await retryOperation(operation, environment);
        return { environment, operationId: result.id };
      }
      return type === 'delete' ? deleteEnvironment(environment) : extendEnvironment(environment, new Date(Math.min(
        Date.parse(environment.expiresAt) + 4 * 3_600_000,
        Date.parse(environment.createdAt) + 72 * 3_600_000,
      )).toISOString());
    },
    retry: false,
    onSettled: () => {
      setConfirmDelete(false);
      void client.invalidateQueries({ queryKey: ['environments'] });
      void client.invalidateQueries({ queryKey: ['operations'] });
      void client.invalidateQueries({ queryKey: ['github-builds'] });
      void client.invalidateQueries({ queryKey: ['provisioning'] });
      void client.invalidateQueries({ queryKey: ['audit', environment.id] });
    },
  });

  const handleOpenPreview = async () => {
    if (!activeTenant || !activeProject) return;
    try {
      setTicketError('');
      const res = await createPreviewTicket(activeTenant.tenantId, activeProject.id, environment.id);
      window.open(res.exchangeUrl, '_blank', 'noopener,noreferrer');
    } catch (err: any) {
      setTicketError(err?.message || 'Failed to generate preview exchange ticket.');
    }
  };

  const active = environment.desiredState === 'ACTIVE';
  const unexpired = Date.parse(environment.expiresAt) > Date.now();
  const canExtend = active && unexpired && Date.parse(environment.expiresAt) < Date.parse(environment.createdAt) + 72 * 3_600_000 - 1000;
  const phase = environment.phase === 'WAITING_BUILD' ? 'Waiting for build'
    : environment.phase === 'QUEUED' ? 'Provisioning pending'
    : environment.phase === 'TERMINATING' ? 'Deletion requested' : environment.phase.replaceAll('_', ' ').toLowerCase();
  const currentOperation = operation?.intentVersion === environment.intentVersion ? operation : undefined;
  const operationState = currentOperation?.state === 'WAITING' ? 'Waiting for prerequisite'
    : currentOperation?.state === 'PENDING' && currentOperation.failureCount > 0 ? 'Retry scheduled'
    : currentOperation?.state === 'PENDING' ? 'Queued'
    : currentOperation?.state === 'FAILED' ? 'Needs attention'
    : currentOperation?.state.toLowerCase();
  const buildVerified = build?.state === 'VERIFIED';
  const buildLabel: Record<string, string> = {
    GITHUB_SETUP_REQUIRED: 'GitHub App setup required', REPOSITORY_NOT_ENROLLED: 'Repository approval required',
    REPOSITORY_DISABLED: 'Repository access paused', RESOLVING_SOURCE: 'Resolving source commit',
    BUILD_REQUESTED: 'Build queued', BUILDING: 'Waiting for successful CI and verification',
    BUILD_DISPATCH_FAILED: 'Build dispatch needs attention', VERIFIED: 'Build verified',
  };

  const isReady = environment.phase === 'READY';

  return <article className="environment-card">
    <div className="environment-main"><span className="environment-icon"><Box size={21} /></span><div className="environment-identity"><strong>{environment.repository}</strong><span>{environment.requestedRef} <span aria-hidden="true">·</span> v{environment.intentVersion}</span></div><span className={`phase-badge ${environment.phase === 'READY' ? 'ready' : active ? 'waiting' : 'terminating'}`}>{phase}</span></div>
    <div className="environment-meta"><span title={environment.namespace}>{environment.namespace}</span><span><Clock3 size={12} />Expires {new Date(environment.expiresAt).toLocaleString()}</span></div>
    <p className="environment-explanation">{environment.phase === 'DESTROYED' ? 'Kubernetes confirmed resource removal. Cleanup remains monitored.' : active ? provisioning?.stage ? provisioning.message : buildVerified ? 'Build verified. Kubernetes provisioning is pending.' : 'Request saved. Waiting for trusted GitHub build evidence.' : 'Absence recorded. Cleanup is pending; removal has not been confirmed.'}</p>
    {active && build && <div className={`build-progress ${buildVerified ? 'build-verified' : ''}`}>
      <div className="operation-heading"><strong>{buildLabel[build.state] ?? 'Checking build verification'}</strong>{build.runUrl && <a href={build.runUrl} target="_blank" rel="noreferrer">View verified run ↗</a>}</div>
      <p>{build.sourceSha ? <>Pinned commit <code title={build.sourceSha}>{build.sourceSha.slice(0, 12)}</code></> : 'The source commit has not been resolved yet.'}</p>
      {buildVerified && <div className="build-images"><code>{build.apiImage}@{build.apiDigest}</code><code>{build.webImage}@{build.webDigest}</code></div>}
    </div>}
    {provisioning?.stage && <div className="build-progress"><div className="operation-heading"><strong>{provisioning.stage.replaceAll('_', ' ').toLowerCase()}</strong><span>{provisioning.capacityReserved ? 'Capacity reserved' : 'Capacity released'}</span></div><p>Argo application <code>{provisioning.application}</code></p>{provisioning.observedAt && <small>Observed {new Date(provisioning.observedAt).toLocaleTimeString()}</small>}</div>}
    {trackProgress && <div className={`operation-progress ${currentOperation?.state === 'FAILED' ? 'operation-failed' : ''}`}>
      {currentOperation ? <><div className="operation-heading"><strong>{operationState}</strong><span>Operation {currentOperation.id.slice(0, 8)}</span></div>
        <p>{currentOperation.message}</p>
        <small>{currentOperation.attempts} checks · {currentOperation.failureCount} failures · Updated {new Date(currentOperation.updatedAt).toLocaleTimeString()}
          {['WAITING', 'PENDING'].includes(currentOperation.state) && <> · Next check after {new Date(currentOperation.availableAt).toLocaleTimeString()}</>}</small>
        {currentOperation.state === 'FAILED' && <><small>Automatic retries stopped. Retry after resolving the reported issue.</small>{canManage && active && unexpired && <button className="small-button" disabled={action.isPending} onClick={() => action.mutate('retry')}>Retry operation</button>}</>}
      </> : <p>Refreshing operation progress for intent v{environment.intentVersion}…</p>}
    </div>}
    <div className="environment-actions">
      {isReady && (
        <button className="small-button preview-button" onClick={handleOpenPreview} title="Open private preview via one-time exchange ticket">
          <ArrowUpRight size={13} /> Open preview
        </button>
      )}
      <button className="small-button diagnostics-button" onClick={() => setShowDiagnostics(true)} title="View live diagnostics, runtime conditions, and logs">
        <Activity size={13} /> Diagnostics
      </button>
      {canManage && isReady && (
        <button className="small-button review-button" onClick={() => setShowReviewModal(true)} title="Request QA validation for this preview deployment">
          <CheckSquare size={13} /> Request QA
        </button>
      )}
      {canManage && <>
        <button className="small-button" disabled={!canExtend || action.isPending} onClick={() => action.mutate('extend')}><Clock3 size={13} />Extend up to 4h</button>
        <button className="small-button danger-button" disabled={!active || action.isPending} onClick={() => setConfirmDelete(true)}><Trash2 size={13} />Request deletion</button>
        <button className="small-button audit-button" onClick={() => setShowAudit(!showAudit)} aria-expanded={showAudit}><History size={13} />Activity</button>
      </>}
      {action.isPending && <span className="action-feedback" role="status">Saving intent…</span>}
    </div>
    {ticketError && <div role="alert" className="inline-error">{ticketError}</div>}
    {confirmDelete && <div className="delete-confirmation"><p>Request deletion of this generation? It cannot be reactivated.</p><button className="small-button" disabled={action.isPending} onClick={() => setConfirmDelete(false)}>Keep request</button><button className="small-button danger-button" disabled={action.isPending} onClick={() => action.mutate('delete')}>Confirm deletion request</button></div>}
    {action.error && <div role="alert" className="inline-error">{action.error.message}</div>}
    {action.isSuccess && <p role="status" className="action-feedback">Intent saved · tracking operation {action.data.operationId.slice(0, 8)}.</p>}
    {showAudit && <div className="audit-history"><h3>Activity</h3>{audit.isPending ? <p>Loading activity…</p> : audit.error ? <p role="alert">{audit.error.message}</p> : <ol>{audit.data?.map(event => <li key={event.id}><span>{event.action.replaceAll('_', ' ').toLowerCase()}</span><small>{event.actor} · {new Date(event.createdAt).toLocaleString()}</small></li>)}</ol>}</div>}

    {showDiagnostics && (
      <DiagnosticsModal
        environmentId={environment.id}
        repository={environment.repository}
        requestedRef={environment.requestedRef}
        onClose={() => setShowDiagnostics(false)}
      />
    )}

    {showReviewModal && (
      <RequestReviewModal
        environmentId={environment.id}
        repository={environment.repository}
        servingRevision={build?.sourceSha ?? undefined}
        onClose={() => setShowReviewModal(false)}
        onSuccess={() => {
          client.invalidateQueries({ queryKey: ['reviews'] });
        }}
      />
    )}
  </article>;
}

