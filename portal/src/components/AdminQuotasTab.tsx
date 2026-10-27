import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Cpu, HardDrive, Layers, Clock, ShieldCheck, AlertCircle, Edit3, CheckCircle2
} from 'lucide-react';
import {
  getTenantQuotas, updateTenantQuotas, getProjectQuotas, updateProjectQuotas,
  getProjects, type TenantQuota, type ProjectQuota, type UpdateQuotaPayload
} from '../api';

export function AdminQuotasTab({ tenantId }: { tenantId: string }) {
  const queryClient = useQueryClient();
  const [editingTenant, setEditingTenant] = useState<TenantQuota | null>(null);
  const [editingProject, setEditingProject] = useState<ProjectQuota | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [updateSuccess, setUpdateSuccess] = useState<string | null>(null);

  const tenantQuotasQuery = useQuery({
    queryKey: ['tenant-quotas', tenantId],
    queryFn: () => getTenantQuotas(tenantId),
  });

  const projectsQuery = useQuery({
    queryKey: ['projects', tenantId],
    queryFn: () => getProjects(tenantId),
  });

  const [selectedProjectId, setSelectedProjectId] = useState<string>('');

  const activeProjectId = selectedProjectId || (projectsQuery.data && projectsQuery.data[0]?.id) || '';

  const projectQuotasQuery = useQuery({
    queryKey: ['project-quotas', tenantId, activeProjectId],
    queryFn: () => getProjectQuotas(tenantId, activeProjectId),
    enabled: !!activeProjectId,
  });

  const updateTenantMutation = useMutation({
    mutationFn: (payload: UpdateQuotaPayload) => updateTenantQuotas(tenantId, payload),
    onSuccess: () => {
      setEditingTenant(null);
      setUpdateError(null);
      setUpdateSuccess('Tenant quota policy updated successfully.');
      setTimeout(() => setUpdateSuccess(null), 4000);
      void queryClient.invalidateQueries({ queryKey: ['tenant-quotas', tenantId] });
    },
    onError: (err: any) => {
      setUpdateError(err?.message || 'Failed to update tenant quotas.');
    },
  });

  const updateProjectMutation = useMutation({
    mutationFn: (payload: UpdateQuotaPayload) => updateProjectQuotas(tenantId, activeProjectId, payload),
    onSuccess: () => {
      setEditingProject(null);
      setUpdateError(null);
      setUpdateSuccess('Project quota policy updated successfully.');
      setTimeout(() => setUpdateSuccess(null), 4000);
      void queryClient.invalidateQueries({ queryKey: ['project-quotas', tenantId, activeProjectId] });
    },
    onError: (err: any) => {
      setUpdateError(err?.message || 'Failed to update project quotas.');
    },
  });

  const quota = tenantQuotasQuery.data;
  const projectQuota = projectQuotasQuery.data;

  return (
    <div className="admin-quotas-tab">
      {updateSuccess && (
        <div className="status-banner status-ready" style={{ marginBottom: '1.25rem' }}>
          <CheckCircle2 size={18} /> {updateSuccess}
        </div>
      )}
      {updateError && (
        <div className="error-banner" style={{ marginBottom: '1.25rem' }}>
          <AlertCircle size={18} /> {updateError}
        </div>
      )}

      {/* Organization Quotas Card */}
      <section className="settings-card" style={{ marginBottom: '2rem' }}>
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <Layers size={22} className="accent-icon" />
            <div>
              <h2>Organization Quotas &amp; Ceilings</h2>
              <p>Global concurrency limits, resource budgets, and maximum TTL for preview environments.</p>
            </div>
          </div>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            {quota && (
              <span className="badge" style={{ background: 'rgba(56, 189, 248, 0.1)', color: '#38bdf8', border: '1px solid rgba(56, 189, 248, 0.3)' }}>
                Policy v{quota.policyVersion}
              </span>
            )}
            <button
              className="spawn-button"
              style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
              disabled={tenantQuotasQuery.isPending}
              onClick={() => {
                if (quota) {
                  setUpdateError(null);
                  setEditingTenant(quota);
                }
              }}
            >
              <Edit3 size={15} /> Edit Quotas
            </button>
          </div>
        </div>

        {tenantQuotasQuery.isPending ? (
          <p className="muted">Loading organization quotas…</p>
        ) : tenantQuotasQuery.error ? (
          <p className="inline-error">{tenantQuotasQuery.error.message}</p>
        ) : quota ? (
          <div className="quota-gauges-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1rem', marginTop: '1rem' }}>
            <QuotaGauge
              icon={<Layers size={18} />}
              title="Active Environments"
              used={quota.consumption.activeEnvironments}
              total={quota.maxEnvironments}
              unit="envs"
            />
            <QuotaGauge
              icon={<Clock size={18} />}
              title="Queued Concurrency"
              used={quota.consumption.queuedEnvironments}
              total={quota.maxQueuedEnvironments}
              unit="queued"
            />
            <QuotaGauge
              icon={<Cpu size={18} />}
              title="CPU Allocation"
              used={quota.consumption.usedCpuMillicores}
              total={quota.cpuMillicoresQuota}
              unit="m"
            />
            <QuotaGauge
              icon={<HardDrive size={18} />}
              title="Memory Allocation"
              used={quota.consumption.usedMemoryMb}
              total={quota.memoryMbQuota}
              unit="MB"
            />
            <div className="quota-gauge-card" style={{ background: 'rgba(255, 255, 255, 0.03)', border: '1px solid rgba(255, 255, 255, 0.08)', borderRadius: '8px', padding: '1rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: '#94a3b8', fontSize: '0.85rem', marginBottom: '0.5rem' }}>
                <Clock size={18} />
                <span>Maximum Preview TTL</span>
              </div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#f8fafc' }}>
                {quota.maxTtlHours} <span style={{ fontSize: '0.9rem', fontWeight: 400, color: '#94a3b8' }}>hours</span>
              </div>
              <small className="muted" style={{ display: 'block', marginTop: '0.5rem' }}>
                Environments expire automatically after TTL.
              </small>
            </div>
          </div>
        ) : null}
      </section>

      {/* Project-Specific Quotas Card */}
      <section className="settings-card">
        <div className="settings-card-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            <ShieldCheck size={22} className="accent-icon" />
            <div>
              <h2>Project-Level Quotas</h2>
              <p>Scoped capacity constraints partitioned within organization ceilings.</p>
            </div>
          </div>
          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
            {projectsQuery.data && projectsQuery.data.length > 0 && (
              <select
                value={activeProjectId}
                onChange={(e) => setSelectedProjectId(e.target.value)}
                style={{ background: '#1e293b', color: '#f8fafc', border: '1px solid #334155', borderRadius: '6px', padding: '0.4rem 0.8rem' }}
              >
                {projectsQuery.data.map((p) => (
                  <option key={p.id} value={p.id}>{p.name} ({p.projectKey})</option>
                ))}
              </select>
            )}
            {projectQuota && (
              <button
                className="spawn-button"
                style={{ fontSize: '0.85rem', padding: '0.4rem 0.8rem' }}
                onClick={() => {
                  setUpdateError(null);
                  setEditingProject(projectQuota);
                }}
              >
                <Edit3 size={15} /> Edit Project Quotas
              </button>
            )}
          </div>
        </div>

        {projectQuotasQuery.isPending ? (
          <p className="muted">Loading project quotas…</p>
        ) : projectQuotasQuery.error ? (
          <p className="inline-error">{projectQuotasQuery.error.message}</p>
        ) : projectQuota ? (
          <div className="quota-gauges-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1rem', marginTop: '1rem' }}>
            <QuotaGauge
              icon={<Layers size={18} />}
              title="Project Active Envs"
              used={projectQuota.consumption.activeEnvironments}
              total={projectQuota.maxEnvironments}
              unit="envs"
            />
            <QuotaGauge
              icon={<Cpu size={18} />}
              title="Project CPU Budget"
              used={projectQuota.consumption.usedCpuMillicores}
              total={projectQuota.cpuMillicoresQuota}
              unit="m"
            />
            <QuotaGauge
              icon={<HardDrive size={18} />}
              title="Project Memory Budget"
              used={projectQuota.consumption.usedMemoryMb}
              total={projectQuota.memoryMbQuota}
              unit="MB"
            />
          </div>
        ) : (
          <p className="muted">No project selected.</p>
        )}
      </section>

      {/* Edit Organization Quotas Modal */}
      {editingTenant && (
        <EditQuotaModal
          title="Update Organization Quota Policy"
          quota={editingTenant}
          isPending={updateTenantMutation.isPending}
          error={updateError}
          onClose={() => setEditingTenant(null)}
          onSave={(payload) => updateTenantMutation.mutate(payload)}
        />
      )}

      {/* Edit Project Quotas Modal */}
      {editingProject && (
        <EditQuotaModal
          title="Update Project Quota Policy"
          quota={editingProject}
          isPending={updateProjectMutation.isPending}
          error={updateError}
          ceiling={quota}
          onClose={() => setEditingProject(null)}
          onSave={(payload) => updateProjectMutation.mutate(payload)}
        />
      )}
    </div>
  );
}

function QuotaGauge({
  icon, title, used, total, unit
}: {
  icon: React.ReactNode;
  title: string;
  used: number;
  total: number;
  unit: string;
}) {
  const percentage = Math.min(100, Math.round((used / Math.max(1, total)) * 100));
  const isHigh = percentage >= 80;
  const isFull = percentage >= 100;

  return (
    <div className="quota-gauge-card" style={{ background: 'rgba(255, 255, 255, 0.03)', border: '1px solid rgba(255, 255, 255, 0.08)', borderRadius: '8px', padding: '1rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: '#94a3b8', fontSize: '0.85rem' }}>
          {icon}
          <span>{title}</span>
        </div>
        <span style={{ fontSize: '0.8rem', fontWeight: 600, color: isFull ? '#ef4444' : isHigh ? '#f59e0b' : '#10b981' }}>
          {percentage}%
        </span>
      </div>
      <div style={{ fontSize: '1.25rem', fontWeight: 700, color: '#f8fafc', marginBottom: '0.5rem' }}>
        {used} <span style={{ fontSize: '0.85rem', fontWeight: 400, color: '#94a3b8' }}>/ {total} {unit}</span>
      </div>
      <div style={{ height: '6px', background: 'rgba(255, 255, 255, 0.1)', borderRadius: '3px', overflow: 'hidden' }}>
        <div
          style={{
            height: '100%',
            width: `${percentage}%`,
            background: isFull ? '#ef4444' : isHigh ? '#f59e0b' : '#38bdf8',
            transition: 'width 0.3s ease',
          }}
        />
      </div>
    </div>
  );
}

function EditQuotaModal({
  title,
  quota,
  ceiling,
  isPending,
  error,
  onClose,
  onSave,
}: {
  title: string;
  quota: TenantQuota | ProjectQuota;
  ceiling?: TenantQuota;
  isPending: boolean;
  error: string | null;
  onClose: () => void;
  onSave: (payload: UpdateQuotaPayload) => void;
}) {
  const [maxEnvs, setMaxEnvs] = useState(quota.maxEnvironments);
  const [maxQueued, setMaxQueued] = useState(('maxQueuedEnvironments' in quota ? quota.maxQueuedEnvironments : 10));
  const [cpu, setCpu] = useState(quota.cpuMillicoresQuota);
  const [memory, setMemory] = useState(quota.memoryMbQuota);
  const [ttl, setTtl] = useState(quota.maxTtlHours);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave({
      maxEnvironments: Number(maxEnvs),
      maxQueuedEnvironments: Number(maxQueued),
      cpuMillicoresQuota: Number(cpu),
      memoryMbQuota: Number(memory),
      storageMbQuota: 51200,
      maxTtlHours: Number(ttl),
      policyVersion: quota.policyVersion,
    });
  };

  return (
    <div className="modal-backdrop" onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ background: '#0f172a', border: '1px solid #334155', borderRadius: '12px', padding: '1.75rem', width: '100%', maxWidth: '520px' }}>
        <h3 style={{ margin: '0 0 0.5rem 0', fontSize: '1.25rem', color: '#f8fafc' }}>{title}</h3>
        <p className="muted" style={{ margin: '0 0 1.25rem 0', fontSize: '0.85rem' }}>
          Updates are protected by optimistic concurrency checking (Policy v{quota.policyVersion}).
        </p>

        {error && (
          <div className="error-banner" style={{ marginBottom: '1rem' }}>
            <AlertCircle size={16} /> {error}
          </div>
        )}

        {ceiling && (
          <div className="info-banner" style={{ marginBottom: '1rem', fontSize: '0.8rem', background: 'rgba(56, 189, 248, 0.08)', border: '1px solid rgba(56, 189, 248, 0.2)', padding: '0.5rem 0.75rem', borderRadius: '6px', color: '#7dd3fc' }}>
            Ceilings imposed by organization: Max {ceiling.maxEnvironments} envs, {ceiling.cpuMillicoresQuota}m CPU, {ceiling.memoryMbQuota}MB RAM.
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <label>
            Max Active Environments
            <input
              type="number"
              min="1"
              max={ceiling ? ceiling.maxEnvironments : 50}
              required
              value={maxEnvs}
              onChange={(e) => setMaxEnvs(Number(e.target.value))}
            />
          </label>

          {'maxQueuedEnvironments' in quota && (
            <label>
              Max Queued Environments
              <input
                type="number"
                min="0"
                max="100"
                required
                value={maxQueued}
                onChange={(e) => setMaxQueued(Number(e.target.value))}
              />
            </label>
          )}

          <label>
            CPU Quota (millicores)
            <input
              type="number"
              step="500"
              min="1000"
              max={ceiling ? ceiling.cpuMillicoresQuota : 100000}
              required
              value={cpu}
              onChange={(e) => setCpu(Number(e.target.value))}
            />
          </label>

          <label>
            Memory Quota (MB)
            <input
              type="number"
              step="512"
              min="1024"
              max={ceiling ? ceiling.memoryMbQuota : 200000}
              required
              value={memory}
              onChange={(e) => setMemory(Number(e.target.value))}
            />
          </label>

          <label>
            Maximum TTL (hours)
            <input
              type="number"
              min="1"
              max={ceiling ? ceiling.maxTtlHours : 720}
              required
              value={ttl}
              onChange={(e) => setTtl(Number(e.target.value))}
            />
          </label>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '1rem' }}>
            <button type="button" className="ghost-button" onClick={onClose} disabled={isPending}>
              Cancel
            </button>
            <button type="submit" className="spawn-button" disabled={isPending}>
              {isPending ? 'Saving…' : 'Save Changes'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
