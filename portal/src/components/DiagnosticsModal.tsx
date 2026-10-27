import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  X,
  Activity,
  CheckCircle2,
  AlertCircle,
  Clock,
  Terminal,
  ExternalLink,
  ShieldCheck,
  Server,
  Layers,
  Copy,
  Check
} from 'lucide-react';
import { getPreviewDiagnostics, createPreviewTicket } from '../api';
import { useWorkspace } from '../context/WorkspaceContext';

interface DiagnosticsModalProps {
  environmentId: string;
  repository: string;
  requestedRef: string;
  onClose: () => void;
}

export function DiagnosticsModal({
  environmentId,
  repository,
  requestedRef,
  onClose,
}: DiagnosticsModalProps) {
  const { activeTenant, activeProject, role } = useWorkspace();
  const [activeTab, setActiveTab] = useState<'CONDITIONS' | 'SPECS' | 'LOGS'>('CONDITIONS');
  const [copied, setCopied] = useState(false);
  const [openingPreview, setOpeningPreview] = useState(false);
  const [previewError, setPreviewError] = useState('');

  const tenantId = activeTenant?.tenantId || '';
  const projectId = activeProject?.id || '';

  const diagnostics = useQuery({
    queryKey: ['diagnostics', tenantId, projectId, environmentId],
    queryFn: () => getPreviewDiagnostics(tenantId, projectId, environmentId),
    refetchInterval: 4_000,
    enabled: Boolean(tenantId && projectId && environmentId),
  });

  const data = diagnostics.data;

  const handleOpenPreview = async () => {
    if (!tenantId || !projectId) return;
    try {
      setOpeningPreview(true);
      setPreviewError('');
      const ticketRes = await createPreviewTicket(tenantId, projectId, environmentId);
      window.open(ticketRes.exchangeUrl, '_blank', 'noopener,noreferrer');
    } catch (err: any) {
      setPreviewError(err?.message || 'Failed to exchange preview ticket.');
    } finally {
      setOpeningPreview(false);
    }
  };

  const handleCopyId = () => {
    navigator.clipboard.writeText(environmentId);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="diagnostics-modal-container"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="diagnostics-title"
      >
        <div className="modal-header">
          <div>
            <div className="diagnostics-eyebrow">
              <Activity size={13} />
              <span>LIVE DIAGNOSTICS &middot; {repository}</span>
            </div>
            <h2 id="diagnostics-title" className="diagnostics-title">
              Branch: <code>{requestedRef}</code>
            </h2>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Close dialog">
            <X size={18} />
          </button>
        </div>

        {diagnostics.isLoading ? (
          <div className="diagnostics-loading">
            <Activity className="spin" size={24} />
            <p>Loading environment diagnostics and runtime conditions…</p>
          </div>
        ) : diagnostics.isError ? (
          <div className="diagnostics-error">
            <AlertCircle size={24} />
            <p>Failed to load diagnostics. Please check your connection and project access.</p>
          </div>
        ) : data ? (
          <>
            <div className="diagnostics-status-bar">
              <div className="status-item">
                <span className="status-label">Phase</span>
                <span className={`phase-badge ${data.phase.toLowerCase()}`}>{data.phase}</span>
              </div>
              <div className="status-item">
                <span className="status-label">Gateway Routing</span>
                <span className={`routing-badge ${data.previewRoutingReady ? 'ready' : 'pending'}`}>
                  {data.previewRoutingReady ? 'Ready' : 'Pending'}
                </span>
              </div>
              <div className="status-item">
                <span className="status-label">Namespace</span>
                <code className="code-pill">{data.namespace}</code>
              </div>
              <div className="status-item">
                <span className="status-label">Expires</span>
                <span className="expiry-text">
                  <Clock size={11} /> {new Date(data.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
            </div>

            {previewError && (
              <div className="diagnostics-alert-banner danger">
                <AlertCircle size={15} />
                <span>{previewError}</span>
              </div>
            )}

            <div className="diagnostics-tab-nav">
              <button
                className={`diagnostics-tab-btn ${activeTab === 'CONDITIONS' ? 'active' : ''}`}
                onClick={() => setActiveTab('CONDITIONS')}
              >
                <ShieldCheck size={14} /> Conditions ({data.conditions.length})
              </button>
              <button
                className={`diagnostics-tab-btn ${activeTab === 'SPECS' ? 'active' : ''}`}
                onClick={() => setActiveTab('SPECS')}
              >
                <Server size={14} /> Runtime Specs
              </button>
              <button
                className={`diagnostics-tab-btn ${activeTab === 'LOGS' ? 'active' : ''}`}
                onClick={() => setActiveTab('LOGS')}
              >
                <Terminal size={14} /> Recent Logs ({data.recentLogs.length})
              </button>
            </div>

            <div className="diagnostics-content-body">
              {activeTab === 'CONDITIONS' && (
                <div className="conditions-list">
                  {data.conditions.map(cond => {
                    const isSuccess = cond.status === 'True';
                    const isPending = cond.status === 'Pending' || cond.status === 'In_Progress';
                    return (
                      <div key={cond.type} className={`condition-card ${cond.status.toLowerCase()}`}>
                        <div className="condition-status-icon">
                          {isSuccess ? (
                            <CheckCircle2 size={18} className="text-success" />
                          ) : isPending ? (
                            <Clock size={18} className="text-pending" />
                          ) : (
                            <AlertCircle size={18} className="text-danger" />
                          )}
                        </div>
                        <div className="condition-info">
                          <div className="condition-header">
                            <strong>{cond.type}</strong>
                            <span className={`status-pill ${cond.status.toLowerCase()}`}>{cond.status}</span>
                          </div>
                          <p className="condition-message">{cond.message}</p>
                          <small className="condition-reason">Reason: <code>{cond.reason}</code></small>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}

              {activeTab === 'SPECS' && (
                <div className="specs-table-container">
                  <table className="specs-table">
                    <tbody>
                      <tr>
                        <th>Environment ID</th>
                        <td>
                          <code>{data.environmentId}</code>
                          <button className="copy-btn" onClick={handleCopyId} title="Copy ID">
                            {copied ? <Check size={12} /> : <Copy size={12} />}
                          </button>
                        </td>
                      </tr>
                      <tr>
                        <th>Serving Revision</th>
                        <td>
                          {data.servingRevision ? (
                            <code>{data.servingRevision.substring(0, 12)}</code>
                          ) : (
                            <span className="text-muted">Not yet serving</span>
                          )}
                        </td>
                      </tr>
                      <tr>
                        <th>Provisioning Stage</th>
                        <td>{data.stage} &middot; <span className="stage-msg">{data.stageMessage}</span></td>
                      </tr>
                      <tr>
                        <th>Desired State</th>
                        <td><code>{data.desiredState}</code></td>
                      </tr>
                      <tr>
                        <th>Created At</th>
                        <td>{new Date(data.createdAt).toLocaleString()}</td>
                      </tr>
                      <tr>
                        <th>Expires At</th>
                        <td>{new Date(data.expiresAt).toLocaleString()} ({data.isExpired ? 'EXPIRED' : 'Active'})</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              )}

              {activeTab === 'LOGS' && (
                <div className="terminal-logs-container">
                  {data.recentLogs.length === 0 ? (
                    <div className="empty-logs">No activity log entries recorded yet.</div>
                  ) : (
                    <div className="terminal-body">
                      {data.recentLogs.map((log, index) => (
                        <div key={index} className="log-line">
                          <span className="log-prefix">&gt;</span>
                          <span className="log-text">{log}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="diagnostics-footer">
              <div className="footer-left">
                {role === 'QA_REVIEWER' ? (
                  <span className="qa-notice-pill" title="QA preview access requires an active review assignment">
                    <ShieldCheck size={13} /> QA Review Assignment Required
                  </span>
                ) : (
                  <span className="isolation-text">
                    <Layers size={13} /> Path-scoped isolation active (`/e/{data.environmentId.substring(0, 8)}…/`)
                  </span>
                )}
              </div>
              <div className="footer-right">
                <button className="secondary-button" onClick={onClose}>
                  Close
                </button>
                {data.phase === 'READY' && role !== 'QA_REVIEWER' && (
                  <button
                    className="primary-button"
                    onClick={handleOpenPreview}
                    disabled={openingPreview}
                  >
                    <ExternalLink size={14} />
                    {openingPreview ? 'Generating Ticket…' : 'Open Preview'}
                  </button>
                )}
              </div>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}
