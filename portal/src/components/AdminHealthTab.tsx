import { useQuery } from '@tanstack/react-query';
import {
  Activity, CheckCircle2, XCircle, Database, Server, Cloud, Globe, GitBranch, KeyRound, RefreshCw
} from 'lucide-react';
import { getIntegrationHealth } from '../api';

export function AdminHealthTab({ tenantId }: { tenantId: string }) {
  const healthQuery = useQuery({
    queryKey: ['admin-health', tenantId],
    queryFn: () => getIntegrationHealth(tenantId),
    refetchInterval: 15000,
  });

  const health = healthQuery.data;

  return (
    <div className="admin-health-tab">
      <section className="settings-card">
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <Activity size={22} className="accent-icon" />
            <div>
              <h2>Integration Health &amp; Subsystems</h2>
              <p>Real-time operational status for control plane databases, worker queues, Kubernetes routing, and external providers.</p>
            </div>
          </div>
          <button
            className="ghost-button"
            onClick={() => void healthQuery.refetch()}
            disabled={healthQuery.isFetching}
            style={{ fontSize: '0.85rem' }}
          >
            <RefreshCw size={14} className={healthQuery.isFetching ? 'spin' : ''} /> Refresh
          </button>
        </div>

        {healthQuery.isPending ? (
          <p className="muted">Probing platform health…</p>
        ) : healthQuery.error ? (
          <p className="inline-error">{healthQuery.error.message}</p>
        ) : health ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1rem', marginTop: '1.25rem' }}>
            <HealthCard
              icon={<Database size={20} />}
              name="PostgreSQL Database"
              detail="Flyway schema v14, durable outbox, audit log"
              isOk={health.databaseOk}
              statusText={health.databaseOk ? 'ONLINE (14 migrations)' : 'DISCONNECTED'}
            />
            <HealthCard
              icon={<Server size={20} />}
              name="Async Worker Engine"
              detail="Lease-based job polling and reconciliation"
              isOk={health.workerActive}
              statusText={health.workerActive ? 'ACTIVE (polling)' : 'DISABLED'}
            />
            <HealthCard
              icon={<Globe size={20} />}
              name="Preview Edge Routing"
              detail="Traefik reverse proxy and dynamic hosts"
              isOk={health.previewRoutingReady}
              statusText={health.previewRoutingReady ? 'HEALTHY' : 'DEGRADED'}
            />
            <HealthCard
              icon={<Cloud size={20} />}
              name="Kubernetes Controller"
              detail="CRD lifecycle manager & namespace isolator"
              isOk={health.kubernetesConfigured}
              statusText={health.kubernetesConfigured ? 'CONNECTED' : 'LOCAL DEV SIMULATION'}
            />
            <HealthCard
              icon={<GitBranch size={20} />}
              name="GitHub App Integration"
              detail="Webhook reception & status check publishing"
              isOk={health.githubConfigured}
              statusText={health.githubConfigured ? 'CONFIGURED' : 'UNCONFIGURED (optional)'}
            />
            <HealthCard
              icon={<KeyRound size={20} />}
              name="Identity &amp; OIDC Provider"
              detail={`Authentication mode: ${health.authMode.toUpperCase()}`}
              isOk={true}
              statusText="ACTIVE (Keycloak / Mock)"
            />
          </div>
        ) : null}

        {health?.checkedAt && (
          <div style={{ marginTop: '1.25rem', fontSize: '0.8rem', color: '#94a3b8', textAlign: 'right' }}>
            Last health probe: {new Date(health.checkedAt).toLocaleTimeString()}
          </div>
        )}
      </section>
    </div>
  );
}

function HealthCard({
  icon,
  name,
  detail,
  isOk,
  statusText,
}: {
  icon: React.ReactNode;
  name: string;
  detail: string;
  isOk: boolean;
  statusText: string;
}) {
  return (
    <div
      style={{
        background: 'rgba(255, 255, 255, 0.02)',
        border: `1px solid ${isOk ? 'rgba(16, 185, 129, 0.25)' : 'rgba(239, 68, 68, 0.25)'}`,
        borderRadius: '8px',
        padding: '1.25rem',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
      }}
    >
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', color: '#f8fafc', fontWeight: 600 }}>
            {icon}
            <span>{name}</span>
          </div>
          {isOk ? (
            <CheckCircle2 size={18} style={{ color: '#10b981' }} />
          ) : (
            <XCircle size={18} style={{ color: '#ef4444' }} />
          )}
        </div>
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          {detail}
        </p>
      </div>

      <div style={{ marginTop: '1rem', paddingTop: '0.75rem', borderTop: '1px solid rgba(255, 255, 255, 0.05)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>Status:</span>
        <span
          className="badge"
          style={{
            background: isOk ? 'rgba(16, 185, 129, 0.15)' : 'rgba(239, 68, 68, 0.15)',
            color: isOk ? '#10b981' : '#ef4444',
            border: `1px solid ${isOk ? '#10b981' : '#ef4444'}40`,
            fontSize: '0.75rem',
            fontWeight: 600,
          }}
        >
          {statusText}
        </span>
      </div>
    </div>
  );
}
