import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Terminal, RefreshCw, GitBranch, Check, CircleDot, Activity, Server, Plus, ArrowUpRight, UserRound, Database, Search, Eye } from 'lucide-react';
import {
  getJson,
  getScopedEnvironments,
  getScopedProvisioningProgress,
  type BuildProgress,
  type EnvironmentPage,
  type Operation,
  type PlatformStatus,
  type ProvisioningProgress
} from '../api';
import { EnvironmentCard } from '../EnvironmentCard';
import { SpawnDialog } from '../SpawnDialog';
import { useWorkspace } from '../context/WorkspaceContext';

export function DeveloperDashboard() {
  const { activeTenant, activeProject, role } = useWorkspace();
  const [spawnOpen, setSpawnOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'QUEUED' | 'TERMINATING'>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const platform = useQuery({
    queryKey: ['platform'],
    queryFn: () => getJson<PlatformStatus>('/api/v1/platform'),
  });

  const tenantId = activeTenant?.tenantId;
  const projectId = activeProject?.id;

  const environments = useQuery({
    queryKey: ['environments', tenantId, projectId],
    queryFn: () => (tenantId && projectId
      ? getScopedEnvironments(tenantId, projectId)
      : getJson<EnvironmentPage>('/api/v1/environments')),
  });

  const items = environments.data?.items ?? [];
  const canSpawn = (role === 'DEVELOPER' || role === 'PORTAL_ADMIN') && (platform.data?.capabilities.includes('lifecycle-intents') ?? false);
  const canManage = canSpawn;
  const canTrack = platform.data?.capabilities.includes('job-processing') ?? false;
  const canTrackBuilds = platform.data?.capabilities.includes('github-builds') ?? false;
  const canTrackProvisioning = platform.data?.capabilities.includes('kubernetes-provisioning') ?? false;

  const operations = useQuery({
    queryKey: ['operations', tenantId],
    queryFn: () => getJson<Operation[]>('/api/v1/operations'),
    enabled: canTrack,
    refetchInterval: 5_000,
  });
  const progress = new Map(operations.data?.map(op => [op.environmentId, op]));

  const builds = useQuery({
    queryKey: ['github-builds', tenantId],
    queryFn: () => getJson<BuildProgress[]>('/api/v1/github/environment-builds'),
    enabled: canTrackBuilds,
    refetchInterval: 5_000,
  });
  const buildProgress = new Map(builds.data?.map(b => [b.environmentId, b]));

  const provisioning = useQuery({
    queryKey: ['provisioning', tenantId, projectId],
    queryFn: () => (tenantId && projectId
      ? getScopedProvisioningProgress(tenantId, projectId)
      : getJson<ProvisioningProgress[]>('/api/v1/kubernetes/environment-progress')),
    enabled: canTrackProvisioning,
    refetchInterval: 5_000,
  });
  const provisioningProgress = new Map(provisioning.data?.map(v => [v.environmentId, v]));

  const filteredItems = items.filter(env => {
    // Status filter
    if (statusFilter === 'ACTIVE' && !(env.desiredState === 'ACTIVE' && env.phase === 'READY')) return false;
    if (statusFilter === 'QUEUED' && !(env.phase === 'QUEUED' || env.phase === 'WAITING_BUILD' || env.phase === 'PROVISIONING')) return false;
    if (statusFilter === 'TERMINATING' && !(env.desiredState === 'ABSENT' || env.phase === 'TERMINATING')) return false;

    // Search query filter
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchesRepo = env.repository.toLowerCase().includes(q);
      const matchesRef = env.requestedRef.toLowerCase().includes(q);
      const matchesNamespace = env.namespace.toLowerCase().includes(q);
      if (!matchesRepo && !matchesRef && !matchesNamespace) return false;
    }

    return true;
  });

  return (
    <div className="developer-dashboard">
      <div className="page-heading">
        <div>
          <p className="eyebrow">YOUR DEVELOPER WORKSPACE &middot; {activeTenant?.tenantName}</p>
          <h1>Environments</h1>
          <p>
            Project: <strong>{activeProject?.name || 'Default Project'}</strong> &middot; Ephemeral PR preview instances
          </p>
        </div>
        <div className="heading-actions">
          <span className={`role-pill ${role.toLowerCase()}`}>{role}</span>
          {role === 'QA_REVIEWER' ? (
            <span className="qa-mode-tag" title="QA Reviewers have read-only inspection on environments; reviews are managed in QA Reviews">
              <Eye size={14} /> Reviewer Inspection Only
            </span>
          ) : (
            <button
              className="spawn-button"
              disabled={!canSpawn}
              onClick={() => setSpawnOpen(true)}
              title={canSpawn ? 'Create an environment request' : 'Requires lifecycle capabilities'}
            >
              <Plus size={16} /> Spawn Environment
            </button>
          )}
        </div>
      </div>

      <section className="foundation-banner">
        <span className="banner-icon"><Terminal size={21} /></span>
        <div>
          <strong>
            {platform.data?.controllerEnabled
              ? 'Verified builds. Private previews.'
              : platform.data?.githubConfigured
              ? 'Approved builds. Exact source revisions.'
              : 'Connect GitHub to verify builds.'}
          </strong>
          <p>
            {platform.data?.controllerEnabled
              ? 'An environment becomes ready only after its private route accepts an authenticated edge probe and denies anonymous access.'
              : 'Connect GitHub and enable the local Kubernetes integration to provision approved builds.'}
          </p>
        </div>
        <span className="phase-tag">PHASE 08 ACTIVE</span>
      </section>

      {notice && (
        <div role="status" className="success-banner">
          {notice}
          <button onClick={() => setNotice('')} aria-label="Dismiss">Dismiss</button>
        </div>
      )}

      <section className="summary-grid" aria-label="Platform overview">
        <article className="summary-card">
          <span>Active Environments <Box size={17} /></span>
          <strong>{environments.isSuccess ? items.length : '—'}</strong>
          <small>Current tenant quota: 5 instances</small>
        </article>
        <article className="summary-card">
          <span>Control Plane API <Activity size={17} /></span>
          <strong className="text-value">{platform.isSuccess ? 'Connected' : 'Connecting'}</strong>
          <small>{platform.data ? `Spring Boot · ${platform.data.mode}` : 'Checking control plane'}</small>
        </article>
        <article className="summary-card">
          <span>Job Worker <Server size={17} /></span>
          <strong className="text-value">{platform.data?.workerEnabled ? 'Active' : 'Disabled'}</strong>
          <small>Monotonic leases & recovery enabled</small>
        </article>
      </section>

      <section className="environment-panel" id="environments">
        <div className="panel-header">
          <div className="filter-tabs">
            <button
              className={`filter-btn ${statusFilter === 'ALL' ? 'active' : ''}`}
              onClick={() => setStatusFilter('ALL')}
            >
              All ({items.length})
            </button>
            <button
              className={`filter-btn ${statusFilter === 'ACTIVE' ? 'active' : ''}`}
              onClick={() => setStatusFilter('ACTIVE')}
            >
              Ready
            </button>
            <button
              className={`filter-btn ${statusFilter === 'QUEUED' ? 'active' : ''}`}
              onClick={() => setStatusFilter('QUEUED')}
            >
              Building / Queued
            </button>
            <button
              className={`filter-btn ${statusFilter === 'TERMINATING' ? 'active' : ''}`}
              onClick={() => setStatusFilter('TERMINATING')}
            >
              Terminating
            </button>
          </div>
          <div className="panel-controls">
            <div className="filter-search-box">
              <Search size={14} className="search-icon" />
              <input
                type="text"
                placeholder="Search repository, branch, or namespace…"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                className="search-input"
              />
              {searchQuery && (
                <button className="clear-search-btn" onClick={() => setSearchQuery('')}>&times;</button>
              )}
            </div>
            <button
              className="refresh-button"
              aria-label="Refresh environments"
              disabled={environments.isFetching}
              onClick={() => {
                void environments.refetch();
                void platform.refetch();
                if (canTrack) void operations.refetch();
                if (canTrackBuilds) void builds.refetch();
              }}
            >
              <RefreshCw size={16} className={environments.isFetching ? 'spin' : ''} />
            </button>
          </div>
        </div>

        {environments.isSuccess && filteredItems.length === 0 ? (
          <div className="empty-state">
            <div className="empty-illustration">
              <GitBranch size={29} />
              <span><Box size={17} /></span>
            </div>
            <h3>No environments found in this view</h3>
            <p>
              {searchQuery
                ? `No environments match "${searchQuery}". Clear your search or change the status filter.`
                : role === 'QA_REVIEWER'
                ? `No active previews found for ${activeProject?.name || 'this project'}. Once a Developer spawns an environment, it will appear here for inspection.`
                : `Use Spawn Environment to dispatch your first branch build request for ${activeProject?.name || 'this project'}, or select another project.`}
            </p>
            <div className="empty-check"><Check size={14} /> Database connected &middot; tenant isolation active</div>
          </div>
        ) : filteredItems.length > 0 ? (
          <div className="environment-list">
            {filteredItems.map(env => (
              <EnvironmentCard
                key={env.id}
                environment={env}
                canManage={canManage}
                operation={progress.get(env.id)}
                trackProgress={canTrack}
                build={buildProgress.get(env.id)}
                provisioning={provisioningProgress.get(env.id)}
              />
            ))}
          </div>
        ) : (
          <div className="empty-state">
            <CircleDot size={30} />
            <h3>Loading workspace environments…</h3>
            <p>Fetching authorized records from PostgreSQL.</p>
          </div>
        )}
      </section>

      <section className="next-step">
        <div>
          <p className="eyebrow">SAAS FOUNDATION</p>
          <h2>Identity before deployment.</h2>
          <p>Persisted users, tenant memberships, projects, and QA review workflows precede production infrastructure.</p>
        </div>
        <div className="flow">
          <span><UserRound size={17} /> Identity</span>
          <span>&rarr;</span>
          <span><Database size={17} /> Tenant policy</span>
          <span>&rarr;</span>
          <span><Box size={17} /> Private preview<ArrowUpRight size={13} /></span>
        </div>
      </section>

      <SpawnDialog
        open={spawnOpen}
        onClose={() => setSpawnOpen(false)}
        tenantId={tenantId}
        projectId={projectId}
        projectName={activeProject?.name}
        onCreated={result => {
          setNotice(`Request saved for ${result.environment.repository}. Waiting for build; no preview is deployed yet.`);
          void environments.refetch();
        }}
      />
    </div>
  );
}
