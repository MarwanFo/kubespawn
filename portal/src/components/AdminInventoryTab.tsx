import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Layers, Search, AlertTriangle, RefreshCw
} from 'lucide-react';
import { getEnvironmentInventory } from '../api';

const PHASES = ['ALL', 'READY', 'WAITING_BUILD', 'PROVISIONING', 'FAILED', 'TERMINATING', 'DESTROYED'];

export function AdminInventoryTab({
  tenantId,
  onSelectForRecovery,
}: {
  tenantId: string;
  onSelectForRecovery: (envId: string) => void;
}) {
  const [phaseFilter, setPhaseFilter] = useState('ALL');
  const [search, setSearch] = useState('');

  const inventoryQuery = useQuery({
    queryKey: ['admin-inventory', tenantId, phaseFilter],
    queryFn: () => getEnvironmentInventory(tenantId, phaseFilter),
  });

  const items = (inventoryQuery.data || []).filter((item) => {
    if (!search) return true;
    const term = search.toLowerCase();
    return (
      item.repositoryName.toLowerCase().includes(term) ||
      item.requestedRef.toLowerCase().includes(term) ||
      item.ownerActor.toLowerCase().includes(term) ||
      item.id.toLowerCase().includes(term)
    );
  });

  return (
    <div className="admin-inventory-tab">
      <section className="settings-card">
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <Layers size={22} className="accent-icon" />
            <div>
              <h2>Cross-Project Environment Inventory</h2>
              <p>Fleet-wide visibility across all preview environments, resource commitments, and runtime health.</p>
            </div>
          </div>
          <button
            className="ghost-button"
            onClick={() => void inventoryQuery.refetch()}
            disabled={inventoryQuery.isFetching}
            style={{ fontSize: '0.85rem' }}
          >
            <RefreshCw size={14} className={inventoryQuery.isFetching ? 'spin' : ''} /> Refresh
          </button>
        </div>

        {/* Filters and Search Bar */}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', marginTop: '1rem', marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
            {PHASES.map((p) => (
              <button
                key={p}
                className={`filter-pill ${phaseFilter === p ? 'active' : ''}`}
                onClick={() => setPhaseFilter(p)}
                style={{
                  background: phaseFilter === p ? '#38bdf8' : 'rgba(255, 255, 255, 0.05)',
                  color: phaseFilter === p ? '#0f172a' : '#94a3b8',
                  border: '1px solid rgba(255, 255, 255, 0.1)',
                  borderRadius: '20px',
                  padding: '0.25rem 0.75rem',
                  fontSize: '0.8rem',
                  fontWeight: 600,
                  cursor: 'pointer',
                }}
              >
                {p}
              </button>
            ))}
          </div>

          <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '0.5rem', background: '#1e293b', border: '1px solid #334155', borderRadius: '6px', padding: '0.3rem 0.6rem' }}>
            <Search size={14} className="muted" />
            <input
              type="text"
              placeholder="Search repo, branch, actor…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ background: 'transparent', border: 'none', color: '#f8fafc', fontSize: '0.85rem', outline: 'none' }}
            />
          </div>
        </div>

        {/* Inventory List / Table */}
        {inventoryQuery.isPending ? (
          <p className="muted">Loading environment inventory…</p>
        ) : inventoryQuery.error ? (
          <p className="inline-error">{inventoryQuery.error.message}</p>
        ) : items.length === 0 ? (
          <p className="muted" style={{ padding: '1.5rem', textAlign: 'center' }}>
            No environments found matching the criteria.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
            {items.map((env) => (
              <div
                key={env.id}
                style={{
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: `1px solid ${env.phase === 'FAILED' ? 'rgba(239, 68, 68, 0.4)' : 'rgba(255, 255, 255, 0.08)'}`,
                  borderRadius: '8px',
                  padding: '1rem',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.5rem',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '0.5rem' }}>
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <strong style={{ color: '#f8fafc', fontSize: '1rem' }}>{env.repositoryName}</strong>
                      <span className="badge" style={{ fontSize: '0.75rem', background: '#1e293b', color: '#38bdf8' }}>
                        {env.requestedRef}
                      </span>
                      <PhaseBadge phase={env.phase} />
                      <span className="badge" style={{ fontSize: '0.7rem', background: 'rgba(255, 255, 255, 0.05)', color: '#94a3b8' }}>
                        Desired: {env.desiredState}
                      </span>
                    </div>
                    <div style={{ display: 'flex', gap: '1rem', color: '#94a3b8', fontSize: '0.8rem', marginTop: '0.35rem' }}>
                      <span>Owner: <strong style={{ color: '#cbd5e1' }}>{env.ownerActor}</strong></span>
                      <span>Allocated: <strong style={{ color: '#cbd5e1' }}>{env.cpuMillicores}m CPU / {env.memoryMb}MB RAM</strong></span>
                      {env.expiresAt && (
                        <span>Expires: <strong style={{ color: '#cbd5e1' }}>{new Date(env.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</strong></span>
                      )}
                    </div>
                  </div>

                  {env.phase === 'FAILED' && (
                    <button
                      className="spawn-button"
                      style={{ fontSize: '0.8rem', padding: '0.35rem 0.75rem', background: '#ef4444', borderColor: '#ef4444' }}
                      onClick={() => onSelectForRecovery(env.id)}
                    >
                      <AlertTriangle size={14} /> Recover Job
                    </button>
                  )}
                </div>

                {env.lastError && (
                  <div style={{ background: 'rgba(239, 68, 68, 0.1)', border: '1px solid rgba(239, 68, 68, 0.25)', borderRadius: '6px', padding: '0.5rem 0.75rem', fontSize: '0.8rem', color: '#fca5a5' }}>
                    <strong>Last Error:</strong> {env.lastError}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function PhaseBadge({ phase }: { phase: string }) {
  let color = '#94a3b8';
  let bg = 'rgba(148, 163, 184, 0.1)';

  switch (phase) {
    case 'READY':
      color = '#10b981';
      bg = 'rgba(16, 185, 129, 0.15)';
      break;
    case 'WAITING_BUILD':
    case 'PROVISIONING':
    case 'QUEUED':
      color = '#38bdf8';
      bg = 'rgba(56, 189, 248, 0.15)';
      break;
    case 'FAILED':
      color = '#ef4444';
      bg = 'rgba(239, 68, 68, 0.2)';
      break;
    case 'TERMINATING':
    case 'DESTROYED':
      color = '#a855f7';
      bg = 'rgba(168, 85, 247, 0.15)';
      break;
  }

  return (
    <span
      className="badge"
      style={{ background: bg, color, border: `1px solid ${color}40`, fontSize: '0.75rem', fontWeight: 600 }}
    >
      {phase}
    </span>
  );
}
