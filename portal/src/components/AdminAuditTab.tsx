import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  FileText, Search, RefreshCw, ChevronDown, ChevronRight, User
} from 'lucide-react';
import { searchAuditEvents } from '../api';

export function AdminAuditTab({ tenantId }: { tenantId: string }) {
  const [actionFilter, setActionFilter] = useState('');
  const [actorFilter, setActorFilter] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const auditQuery = useQuery({
    queryKey: ['admin-audit', tenantId, actionFilter, actorFilter],
    queryFn: () => searchAuditEvents(tenantId, {
      action: actionFilter || undefined,
      actorFilter: actorFilter || undefined,
      limit: 100,
    }),
  });

  const toggleExpand = (id: string) => {
    setExpandedId(expandedId === id ? null : id);
  };

  const items = auditQuery.data?.items || [];
  const total = auditQuery.data?.total ?? 0;

  return (
    <div className="admin-audit-tab">
      <section className="settings-card">
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <FileText size={22} className="accent-icon" />
            <div>
              <h2>Audit Log &amp; Event History</h2>
              <p>Immutable, tenant-scoped audit trail tracking lifecycle mutations, policy changes, and administrative recoveries.</p>
            </div>
          </div>
          <button
            className="ghost-button"
            onClick={() => void auditQuery.refetch()}
            disabled={auditQuery.isFetching}
            style={{ fontSize: '0.85rem' }}
          >
            <RefreshCw size={14} className={auditQuery.isFetching ? 'spin' : ''} /> Refresh
          </button>
        </div>

        {/* Filter bar */}
        <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', marginTop: '1rem', marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', background: '#1e293b', border: '1px solid #334155', borderRadius: '6px', padding: '0.3rem 0.6rem', flex: '1 1 200px' }}>
            <Search size={14} className="muted" />
            <input
              type="text"
              placeholder="Filter by action (e.g. RECOVERY, QUOTA)…"
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
              style={{ background: 'transparent', border: 'none', color: '#f8fafc', fontSize: '0.85rem', outline: 'none', width: '100%' }}
            />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', background: '#1e293b', border: '1px solid #334155', borderRadius: '6px', padding: '0.3rem 0.6rem', flex: '1 1 200px' }}>
            <User size={14} className="muted" />
            <input
              type="text"
              placeholder="Filter by actor (e.g. local-developer)…"
              value={actorFilter}
              onChange={(e) => setActorFilter(e.target.value)}
              style={{ background: 'transparent', border: 'none', color: '#f8fafc', fontSize: '0.85rem', outline: 'none', width: '100%' }}
            />
          </div>

          {(actionFilter || actorFilter) && (
            <button
              className="ghost-button"
              onClick={() => {
                setActionFilter('');
                setActorFilter('');
              }}
              style={{ fontSize: '0.8rem' }}
            >
              Clear Filters
            </button>
          )}
        </div>

        {/* Table of Events */}
        {auditQuery.isPending ? (
          <p className="muted">Loading audit history…</p>
        ) : auditQuery.error ? (
          <p className="inline-error">{auditQuery.error.message}</p>
        ) : items.length === 0 ? (
          <p className="muted" style={{ padding: '1.5rem', textAlign: 'center' }}>
            No audit events found matching filters.
          </p>
        ) : (
          <div>
            <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginBottom: '0.75rem' }}>
              Showing {items.length} of {total} event(s)
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              {items.map((event) => (
                <div
                  key={event.id}
                  style={{
                    background: 'rgba(255, 255, 255, 0.02)',
                    border: '1px solid rgba(255, 255, 255, 0.08)',
                    borderRadius: '8px',
                    padding: '0.85rem 1rem',
                  }}
                >
                  <div
                    onClick={() => toggleExpand(event.id)}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                      flexWrap: 'wrap',
                      gap: '0.5rem',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
                      {expandedId === event.id ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                      <ActionBadge action={event.action} />
                      <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#f8fafc' }}>
                        {event.actor}
                      </span>
                      {event.environmentId && (
                        <span className="badge" style={{ fontSize: '0.7rem', background: '#1e293b', color: '#94a3b8' }}>
                          env: {event.environmentId.substring(0, 8)}…
                        </span>
                      )}
                    </div>
                    <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>
                      {new Date(event.createdAt).toLocaleString()}
                    </div>
                  </div>

                  {expandedId === event.id && (
                    <div style={{ marginTop: '0.75rem', paddingTop: '0.75rem', borderTop: '1px solid rgba(255, 255, 255, 0.06)' }}>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.5rem', fontSize: '0.8rem', color: '#94a3b8', marginBottom: '0.5rem' }}>
                        <div>Event ID: <code style={{ color: '#cbd5e1' }}>{event.id}</code></div>
                        <div>Request ID: <code style={{ color: '#cbd5e1' }}>{event.requestId}</code></div>
                        {event.projectId && <div>Project ID: <code style={{ color: '#cbd5e1' }}>{event.projectId}</code></div>}
                      </div>

                      {event.details && (
                        <div>
                          <span style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Event Details:</span>
                          <pre style={{
                            background: '#090d16',
                            border: '1px solid rgba(255, 255, 255, 0.08)',
                            borderRadius: '6px',
                            padding: '0.6rem 0.8rem',
                            fontSize: '0.8rem',
                            color: '#38bdf8',
                            overflowX: 'auto',
                            marginTop: '0.3rem',
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all'
                          }}>
                            {formatJson(event.details)}
                          </pre>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

function ActionBadge({ action }: { action: string }) {
  let color = '#38bdf8';
  let bg = 'rgba(56, 189, 248, 0.15)';

  if (action.includes('RECOVERY') || action.includes('CLEANUP')) {
    color = '#f59e0b';
    bg = 'rgba(245, 158, 11, 0.15)';
  } else if (action.includes('FAILED') || action.includes('ERROR')) {
    color = '#ef4444';
    bg = 'rgba(239, 68, 68, 0.15)';
  } else if (action.includes('QUOTA')) {
    color = '#a855f7';
    bg = 'rgba(168, 85, 247, 0.15)';
  } else if (action.includes('CREATED') || action.includes('REQUESTED') || action.includes('APPROVED')) {
    color = '#10b981';
    bg = 'rgba(16, 185, 129, 0.15)';
  }

  return (
    <span className="badge" style={{ background: bg, color, border: `1px solid ${color}40`, fontSize: '0.75rem', fontWeight: 600 }}>
      {action}
    </span>
  );
}

function formatJson(raw: string) {
  try {
    const parsed = JSON.parse(raw);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}
