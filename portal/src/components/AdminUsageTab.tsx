import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  CreditCard, Cpu, HardDrive, CheckCircle2,
  Download, ArrowUpRight, Zap, RefreshCw,
  Layers, AlertTriangle, ShieldCheck
} from 'lucide-react';
import {
  fetchUsageSummary, fetchPlans, changeTenantPlan, fetchUsageIntervals,
  type UsageSummary, type Plan, type UsageInterval
} from '../api';

interface AdminUsageTabProps {
  tenantId: string;
}

export function AdminUsageTab({ tenantId }: AdminUsageTabProps) {
  const queryClient = useQueryClient();
  const [selectedPlanToUpgrade, setSelectedPlanToUpgrade] = useState<Plan | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Queries
  const { data: summary, isLoading: isSummaryLoading } = useQuery<UsageSummary>({
    queryKey: ['usage-summary', tenantId],
    queryFn: () => fetchUsageSummary(tenantId),
  });

  const { data: plans } = useQuery<Plan[]>({
    queryKey: ['plans-catalog'],
    queryFn: () => fetchPlans(),
  });

  const { data: intervalsPage, isLoading: isIntervalsLoading } = useQuery({
    queryKey: ['usage-intervals', tenantId],
    queryFn: () => fetchUsageIntervals(tenantId, { page: 0, size: 10 }),
  });

  // Mutation to change plan
  const planMutation = useMutation({
    mutationFn: (planId: string) => changeTenantPlan(tenantId, planId),
    onSuccess: (data) => {
      setSelectedPlanToUpgrade(null);
      setSuccessMessage(`Workspace subscription successfully updated to ${data.planName}!`);
      setTimeout(() => setSuccessMessage(null), 5000);
      void queryClient.invalidateQueries({ queryKey: ['usage-summary', tenantId] });
      void queryClient.invalidateQueries({ queryKey: ['tenant-quotas', tenantId] });
    },
    onError: (err: Error) => {
      setErrorMessage(err.message || 'Failed to update workspace plan.');
      setTimeout(() => setErrorMessage(null), 6000);
    },
  });

  const handleExportCsv = () => {
    window.open(`/api/v1/tenants/${tenantId}/usage/export`, '_blank');
  };

  if (isSummaryLoading) {
    return (
      <div className="empty-feed">
        <RefreshCw size={24} className="spinning" />
        <p>Loading usage &amp; plan analytics...</p>
      </div>
    );
  }

  const currentPlan = summary?.plan;
  const currentSub = summary?.subscription;

  const envPct = summary ? Math.min(100, Math.round((summary.activeEnvironments / (summary.activeEnvironmentsQuota || 1)) * 100)) : 0;
  const cpuPct = summary ? Math.min(100, Math.round((summary.usedCpuMillicores / (summary.cpuMillicoresQuota || 1)) * 100)) : 0;
  const memPct = summary ? Math.min(100, Math.round((summary.usedMemoryMb / (summary.memoryMbQuota || 1)) * 100)) : 0;
  const storagePct = summary ? Math.min(100, Math.round((summary.usedStorageMb / (summary.storageMbQuota || 1)) * 100)) : 0;

  return (
    <div className="usage-tab-container">
      {/* Messages */}
      {successMessage && (
        <div className="success-banner" style={{ marginBottom: '1.25rem' }}>
          <CheckCircle2 size={18} /> {successMessage}
        </div>
      )}
      {errorMessage && (
        <div className="error-banner" style={{ marginBottom: '1.25rem' }}>
          <AlertTriangle size={18} /> {errorMessage}
        </div>
      )}

      {/* Plan & Subscription Overview Banner */}
      <div className="usage-plan-banner">
        <div className="usage-plan-info">
          <div className="plan-badge-row">
            <span className={`plan-tier-badge tier-${currentSub?.tier?.toLowerCase() || 'free'}`}>
              <Zap size={14} /> {currentSub?.tier || 'FREE'} PLAN
            </span>
            <span className="plan-status-badge status-active">
              {currentSub?.status || 'ACTIVE'}
            </span>
          </div>
          <h2>{currentSub?.planName || 'Starter / Free Plan'}</h2>
          <p className="plan-desc">
            {currentPlan?.description || 'Essential developer preview platform for collaborative engineering.'}
          </p>
          <div className="plan-period-meta">
            <span>Cycle: {new Date(summary?.periodStart || Date.now()).toLocaleDateString()} &ndash; {new Date(summary?.periodEnd || Date.now()).toLocaleDateString()}</span>
            <span className="dot-sep">&bull;</span>
            <span>Billing Provider: {currentSub?.billingProvider || 'Internal Platform'}</span>
          </div>
        </div>

        <div className="usage-cost-card">
          <div className="cost-label">
            <CreditCard size={16} /> Estimated Resource Consumption
          </div>
          <div className="cost-amount">
            ${((summary?.estimatedPeriodCostCents || 0) / 100).toFixed(2)}
          </div>
          <div className="cost-breakdown">
            <span>{summary?.periodCoreHours.toFixed(1)} Core-Hrs</span>
            <span className="dot-sep">&bull;</span>
            <span>{summary?.periodMemoryGbHours.toFixed(1)} GB-Hrs</span>
          </div>
          <div className="cost-disclaimer">
            * Resource allocation estimate. Billed separately from base subscription invoices.
          </div>
        </div>
      </div>

      {/* Current Quota Consumption Meters */}
      <h3 className="section-subheading" style={{ marginTop: '1.5rem', marginBottom: '0.75rem' }}>
        Live Capacity Utilization
      </h3>
      <div className="usage-meters-grid">
        <div className="usage-meter-card">
          <div className="meter-header">
            <span className="meter-icon env-icon"><Layers size={16} /></span>
            <span className="meter-title">Active Previews</span>
            <span className="meter-value">{summary?.activeEnvironments} / {summary?.activeEnvironmentsQuota}</span>
          </div>
          <div className="progress-bar-container">
            <div
              className={`progress-fill ${envPct >= 80 ? 'warning' : ''}`}
              style={{ width: `${envPct}%` }}
            />
          </div>
          <div className="meter-footer">{envPct}% allocated</div>
        </div>

        <div className="usage-meter-card">
          <div className="meter-header">
            <span className="meter-icon cpu-icon"><Cpu size={16} /></span>
            <span className="meter-title">CPU Millicores</span>
            <span className="meter-value">{(summary?.usedCpuMillicores || 0).toLocaleString()} / {(summary?.cpuMillicoresQuota || 0).toLocaleString()} m</span>
          </div>
          <div className="progress-bar-container">
            <div
              className={`progress-fill ${cpuPct >= 80 ? 'warning' : ''}`}
              style={{ width: `${cpuPct}%` }}
            />
          </div>
          <div className="meter-footer">{cpuPct}% allocated</div>
        </div>

        <div className="usage-meter-card">
          <div className="meter-header">
            <span className="meter-icon mem-icon"><Zap size={16} /></span>
            <span className="meter-title">Memory</span>
            <span className="meter-value">{((summary?.usedMemoryMb || 0) / 1024).toFixed(1)} / {((summary?.memoryMbQuota || 0) / 1024).toFixed(1)} GB</span>
          </div>
          <div className="progress-bar-container">
            <div
              className={`progress-fill ${memPct >= 80 ? 'warning' : ''}`}
              style={{ width: `${memPct}%` }}
            />
          </div>
          <div className="meter-footer">{memPct}% allocated</div>
        </div>

        <div className="usage-meter-card">
          <div className="meter-header">
            <span className="meter-icon storage-icon"><HardDrive size={16} /></span>
            <span className="meter-title">Storage</span>
            <span className="meter-value">{((summary?.usedStorageMb || 0) / 1024).toFixed(1)} / {((summary?.storageMbQuota || 0) / 1024).toFixed(1)} GB</span>
          </div>
          <div className="progress-bar-container">
            <div
              className={`progress-fill ${storagePct >= 80 ? 'warning' : ''}`}
              style={{ width: `${storagePct}%` }}
            />
          </div>
          <div className="meter-footer">{storagePct}% allocated</div>
        </div>
      </div>

      {/* Project Usage Breakdown */}
      <h3 className="section-subheading" style={{ marginTop: '2rem', marginBottom: '0.75rem' }}>
        Project Consumption Breakdown
      </h3>
      <div className="settings-card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="admin-table">
          <thead>
            <tr>
              <th>Project</th>
              <th>Active Envs</th>
              <th>Core-Hours</th>
              <th>Memory GB-Hours</th>
              <th>Est. Resource Cost</th>
            </tr>
          </thead>
          <tbody>
            {(!summary?.projectBreakdown || summary.projectBreakdown.length === 0) ? (
              <tr>
                <td colSpan={5} className="empty-cell">No project usage recorded in this cycle.</td>
              </tr>
            ) : (
              summary.projectBreakdown.map((proj) => (
                <tr key={proj.projectId}>
                  <td style={{ fontWeight: 600 }}>{proj.projectName}</td>
                  <td>{proj.activeEnvironments}</td>
                  <td>{proj.totalCoreHours.toFixed(2)} hrs</td>
                  <td>{proj.totalMemoryGbHours.toFixed(2)} GB-hrs</td>
                  <td>${(proj.estimatedCostCents / 100).toFixed(2)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Recent Intervals & FinOps CSV Export */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '2rem', marginBottom: '0.75rem' }}>
        <h3 className="section-subheading" style={{ margin: 0 }}>
          Resource-Time Activity Feed
        </h3>
        <button className="export-csv-btn" onClick={handleExportCsv}>
          <Download size={15} /> Export FinOps CSV
        </button>
      </div>

      <div className="settings-card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="admin-table">
          <thead>
            <tr>
              <th>Environment</th>
              <th>Allocation</th>
              <th>Started</th>
              <th>Duration</th>
              <th>Core-Hours</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {isIntervalsLoading ? (
              <tr><td colSpan={6} className="empty-cell">Loading activity...</td></tr>
            ) : (!intervalsPage?.items || intervalsPage.items.length === 0) ? (
              <tr><td colSpan={6} className="empty-cell">No usage intervals recorded yet.</td></tr>
            ) : (
              intervalsPage.items.map((interval: UsageInterval) => (
                <tr key={interval.id}>
                  <td style={{ fontWeight: 500 }}>{interval.environmentName}</td>
                  <td>{interval.cpuMillicores}m CPU / {interval.memoryMb}MB RAM</td>
                  <td>{new Date(interval.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', month: 'short', day: 'numeric' })}</td>
                  <td>{interval.durationSeconds ? `${Math.round(interval.durationSeconds / 60)}m` : 'Active'}</td>
                  <td>{interval.coreHours.toFixed(2)} hrs</td>
                  <td>
                    <span className={`interval-status-pill ${interval.status.toLowerCase()}`}>
                      {interval.status}
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Plan Catalog & Upgrade Options */}
      <h3 className="section-subheading" style={{ marginTop: '2.5rem', marginBottom: '0.75rem' }}>
        Available Subscription Tiers
      </h3>
      <div className="plans-catalog-grid">
        {(plans || []).map((plan) => {
          const isCurrent = plan.id === currentSub?.planId;
          return (
            <div key={plan.id} className={`plan-card ${isCurrent ? 'current-plan-card' : ''}`}>
              {isCurrent && <div className="current-ribbon">Current Plan</div>}
              <div className="plan-card-header">
                <span className={`plan-tier-chip tier-${plan.tier.toLowerCase()}`}>{plan.tier}</span>
                <h4>{plan.name}</h4>
                <div className="plan-price">
                  <span className="price-num">${plan.priceCentsMonthly / 100}</span>
                  <span className="price-cadence">/ month</span>
                </div>
                <p className="plan-card-desc">{plan.description}</p>
              </div>

              <div className="plan-specs-list">
                <div className="spec-row">
                  <span>Active Environments:</span>
                  <strong>{plan.maxEnvironments}</strong>
                </div>
                <div className="spec-row">
                  <span>CPU Quota:</span>
                  <strong>{plan.cpuMillicoresQuota / 1000} Cores ({plan.cpuMillicoresQuota}m)</strong>
                </div>
                <div className="spec-row">
                  <span>Memory Quota:</span>
                  <strong>{plan.memoryMbQuota / 1024} GB RAM</strong>
                </div>
                <div className="spec-row">
                  <span>Max Environment TTL:</span>
                  <strong>{plan.maxTtlHours} hours</strong>
                </div>
              </div>

              <ul className="plan-features-list">
                {plan.features.map((feat, idx) => (
                  <li key={idx}>
                    <CheckCircle2 size={15} className="feat-check" />
                    <span>{feat}</span>
                  </li>
                ))}
              </ul>

              <div className="plan-action-container">
                {isCurrent ? (
                  <button className="plan-btn current" disabled>
                    <ShieldCheck size={16} /> Subscribed
                  </button>
                ) : (
                  <button
                    className="plan-btn upgrade"
                    onClick={() => setSelectedPlanToUpgrade(plan)}
                    disabled={planMutation.isPending}
                  >
                    <span>Switch to {plan.name}</span>
                    <ArrowUpRight size={16} />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Confirmation Modal */}
      {selectedPlanToUpgrade && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: '480px' }}>
            <div className="modal-header">
              <Zap size={22} style={{ color: '#38bdf8' }} />
              <h3>Confirm Plan Transition</h3>
            </div>
            <p style={{ color: '#94a3b8', fontSize: '0.9rem', lineHeight: '1.5', margin: '0.75rem 0' }}>
              Are you sure you want to transition <strong>Acme Corp</strong> from{' '}
              <strong>{currentSub?.planName}</strong> to{' '}
              <strong>{selectedPlanToUpgrade.name}</strong>?
            </p>
            <div style={{ background: '#0f172a', padding: '0.85rem', borderRadius: '8px', marginBottom: '1.25rem', fontSize: '0.85rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.35rem' }}>
                <span style={{ color: '#64748b' }}>Active Environments Ceiling:</span>
                <strong style={{ color: '#f8fafc' }}>{selectedPlanToUpgrade.maxEnvironments}</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.35rem' }}>
                <span style={{ color: '#64748b' }}>CPU Quota:</span>
                <strong style={{ color: '#f8fafc' }}>{selectedPlanToUpgrade.cpuMillicoresQuota}m</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: '#64748b' }}>Monthly Subscription Rate:</span>
                <strong style={{ color: '#38bdf8' }}>${selectedPlanToUpgrade.priceCentsMonthly / 100} / mo</strong>
              </div>
            </div>

            <div className="modal-actions" style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
              <button
                className="secondary-btn"
                onClick={() => setSelectedPlanToUpgrade(null)}
                disabled={planMutation.isPending}
              >
                Cancel
              </button>
              <button
                className="spawn-button"
                onClick={() => planMutation.mutate(selectedPlanToUpgrade.id)}
                disabled={planMutation.isPending}
              >
                {planMutation.isPending ? 'Updating...' : `Confirm & Switch Plan`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
