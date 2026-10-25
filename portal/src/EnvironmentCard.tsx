import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Clock3, History, Trash2 } from 'lucide-react';
import { deleteEnvironment, extendEnvironment, getJson } from './api';
import type { AuditEvent, Environment } from './api';

export function EnvironmentCard({ environment, canManage }: { environment: Environment; canManage: boolean }) {
  const client = useQueryClient();
  const [showAudit, setShowAudit] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const audit = useQuery({
    queryKey: ['audit', environment.id], queryFn: () => getJson<AuditEvent[]>(`/api/v1/environments/${environment.id}/audit`),
    enabled: showAudit,
  });
  const action = useMutation({
    mutationFn: (type: 'extend' | 'delete') => type === 'delete' ? deleteEnvironment(environment)
      : extendEnvironment(environment, new Date(Math.min(
        Date.parse(environment.expiresAt) + 4 * 3_600_000,
        Date.parse(environment.createdAt) + 72 * 3_600_000,
      )).toISOString()),
    retry: false,
    onSettled: () => {
      setConfirmDelete(false);
      void client.invalidateQueries({ queryKey: ['environments'] });
      void client.invalidateQueries({ queryKey: ['audit', environment.id] });
    },
  });
  const active = environment.desiredState === 'ACTIVE';
  const unexpired = Date.parse(environment.expiresAt) > Date.now();
  const canExtend = active && unexpired && Date.parse(environment.expiresAt) < Date.parse(environment.createdAt) + 72 * 3_600_000 - 1000;
  const phase = environment.phase === 'WAITING_BUILD' ? 'Waiting for build'
    : environment.phase === 'TERMINATING' ? 'Deletion requested' : environment.phase.replaceAll('_', ' ').toLowerCase();

  return <article className="environment-card">
    <div className="environment-main"><span className="environment-icon"><Box size={21} /></span><div className="environment-identity"><strong>{environment.repository}</strong><span>{environment.requestedRef} <span aria-hidden="true">·</span> v{environment.intentVersion}</span></div><span className={`phase-badge ${active ? 'waiting' : 'terminating'}`}>{phase}</span></div>
    <div className="environment-meta"><span title={environment.namespace}>{environment.namespace}</span><span><Clock3 size={12} />Expires {new Date(environment.expiresAt).toLocaleString()}</span></div>
    <p className="environment-explanation">{active ? 'Request saved. Build verification and Kubernetes provisioning are pending.' : 'Absence recorded. Cleanup is pending; removal has not been confirmed.'}</p>
    <div className="environment-actions">
      {canManage && <><button className="small-button" disabled={!canExtend || action.isPending} onClick={() => action.mutate('extend')}><Clock3 size={13} />Extend up to 4h</button><button className="small-button danger-button" disabled={!active || action.isPending} onClick={() => setConfirmDelete(true)}><Trash2 size={13} />Request deletion</button><button className="small-button audit-button" onClick={() => setShowAudit(!showAudit)} aria-expanded={showAudit}><History size={13} />Activity</button></>}
      {action.isPending && <span className="action-feedback" role="status">Saving intent…</span>}
    </div>
    {confirmDelete && <div className="delete-confirmation"><p>Request deletion of this generation? It cannot be reactivated.</p><button className="small-button" disabled={action.isPending} onClick={() => setConfirmDelete(false)}>Keep request</button><button className="small-button danger-button" disabled={action.isPending} onClick={() => action.mutate('delete')}>Confirm deletion request</button></div>}
    {action.error && <div role="alert" className="inline-error">{action.error.message}</div>}
    {action.isSuccess && <p role="status" className="action-feedback">Intent saved · operation {action.data.operationId.slice(0, 8)} is pending.</p>}
    {showAudit && <div className="audit-history"><h3>Activity</h3>{audit.isPending ? <p>Loading activity…</p> : audit.error ? <p role="alert">{audit.error.message}</p> : <ol>{audit.data?.map(event => <li key={event.id}><span>{event.action.replaceAll('_', ' ').toLowerCase()}</span><small>{event.actor} · {new Date(event.createdAt).toLocaleString()}</small></li>)}</ol>}</div>}
  </article>;
}
