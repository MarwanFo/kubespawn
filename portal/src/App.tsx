import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, ArrowUpRight, Box, Check, ChevronRight, CircleDot, Database, GitBranch, Layers3, RefreshCw, Server, ShieldCheck, Terminal } from 'lucide-react';
import { getJson } from './api';
import type { BuildProgress, EnvironmentPage, Operation, PlatformStatus, ProvisioningProgress } from './api';
import { SpawnDialog } from './SpawnDialog';
import { EnvironmentCard } from './EnvironmentCard';

export function App() {
  const client = useQueryClient();
  const [spawnOpen, setSpawnOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const platform = useQuery({ queryKey: ['platform'], queryFn: () => getJson<PlatformStatus>('/api/v1/platform') });
  const environments = useQuery({ queryKey: ['environments'], queryFn: () => getJson<EnvironmentPage>('/api/v1/environments') });
  const items = environments.data?.items ?? [];
  const connected = platform.isSuccess && environments.isSuccess;
  const error = platform.error || environments.error;
  const canManage = platform.data?.capabilities.includes('lifecycle-intents') ?? false;
  const canTrack = platform.data?.capabilities.includes('job-processing') ?? false;
  const operations = useQuery({ queryKey: ['operations'], queryFn: () => getJson<Operation[]>('/api/v1/operations'),
    enabled: canTrack, refetchInterval: 5_000 });
  const progress = new Map(operations.data?.map(operation => [operation.environmentId, operation]));
  const canTrackBuilds = platform.data?.capabilities.includes('github-builds') ?? false;
  const builds = useQuery({ queryKey: ['github-builds'], queryFn: () => getJson<BuildProgress[]>('/api/v1/github/environment-builds'),
    enabled: canTrackBuilds, refetchInterval: 5_000 });
  const buildProgress = new Map(builds.data?.map(build => [build.environmentId, build]));
  const canTrackProvisioning = platform.data?.capabilities.includes('kubernetes-provisioning') ?? false;
  const provisioning = useQuery({ queryKey: ['provisioning'], queryFn: () => getJson<ProvisioningProgress[]>('/api/v1/kubernetes/environment-progress'),
    enabled: canTrackProvisioning, refetchInterval: 5_000 });
  const provisioningProgress = new Map(provisioning.data?.map(value => [value.environmentId, value]));

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="KubeSpawn home"><span className="brand-mark"><Layers3 size={23} /></span>KubeSpawn<span className="brand-dot">.</span></a>
        <div className="workspace"><span className="workspace-avatar">KS</span><div>Local workspace<small>Development</small></div><ChevronRight size={15} /></div>
        <div className="nav-label">WORKSPACE</div>
        <a className="nav-item selected" href="#environments"><Box size={18} />Environments<span className="nav-count">{environments.isSuccess ? items.length : '—'}</span></a>
        <div className="sidebar-note"><ShieldCheck size={17} /><span>Private by design<small>Local access · no cloud credentials</small></span></div>
        <div className="sidebar-footer"><span className={`status-dot ${connected ? 'online' : ''}`} />{connected ? 'API connected' : 'Connecting to API'}<small>Workload provisioning · v{platform.data?.version ?? '0.5.0'}</small></div>
      </aside>

      <div className="main-area">
        <header className="topbar"><span>Workspace <ChevronRight size={13} /> <strong>Environments</strong></span><span className="local-pill"><span className="status-dot online" />LOCAL DEVELOPMENT</span></header>
        <main>
          <div className="page-heading"><div><p className="eyebrow">YOUR DEVELOPER WORKSPACE</p><h1>Environments</h1><p>From pull request to a place you can test.</p></div><button className="spawn-button" disabled={!connected || !canManage} onClick={() => setSpawnOpen(true)} title={canManage ? 'Create an environment request' : 'Lifecycle requests require the updated API in local mode'}><Box size={17} />Spawn environment</button></div>

          <section className="foundation-banner"><span className="banner-icon"><Terminal size={21} /></span><div><strong>{platform.data?.controllerEnabled ? 'Verified builds. Isolated workloads.' : platform.data?.githubConfigured ? 'Approved builds. Exact source revisions.' : 'Connect GitHub to verify builds.'}</strong><p>{platform.data?.controllerEnabled ? 'The controller establishes each namespace before Argo deploys approved images. Private preview routing is the next milestone.' : 'Connect GitHub and enable the local Kubernetes integration to provision approved builds.'} <a href="https://github.com/MarwanFo/kubespawn/blob/main/docs/kubernetes-provisioning.md" target="_blank" rel="noreferrer">Setup guide ↗</a></p></div><span className="phase-tag">PHASE 05</span></section>

          {error && <div role="alert" className="error-banner">{error.message}</div>}
          {canTrack && operations.error && <div role="alert" className="error-banner">Operation progress is unavailable. {operations.error.message}</div>}
          {canTrackBuilds && builds.error && <div role="alert" className="error-banner">Build progress is unavailable. {builds.error.message}</div>}
          {canTrackProvisioning && provisioning.error && <div role="alert" className="error-banner">Provisioning progress is unavailable. {provisioning.error.message}</div>}
          {notice && <div role="status" className="success-banner">{notice}<button onClick={() => setNotice('')} aria-label="Dismiss notification">Dismiss</button></div>}

          <section className="summary-grid" aria-label="Platform overview">
            <article className="summary-card"><span>Recent requests<Box size={17} /></span><strong>{environments.isSuccess ? items.length : '—'}</strong><small>{environments.isSuccess ? 'Latest 50 records from the platform database' : 'Waiting for database response'}</small></article>
            <article className="summary-card"><span>Platform API<Activity size={17} /></span><strong className="text-value">{platform.isSuccess ? 'Connected' : platform.isError ? 'Unavailable' : 'Connecting'}</strong><small>{platform.data ? `Spring Boot · ${platform.data.mode}` : 'Checking the control plane'}</small></article>
            <article className="summary-card"><span>Job worker<Server size={17} /></span><strong className="text-value">{platform.data ? platform.data.workerEnabled ? 'Enabled' : 'Disabled' : 'Checking'}</strong><small>Build and cleanup prerequisites are checked automatically</small></article>
          </section>

          <section className="environment-panel" id="environments">
            <div className="panel-header"><div><h2>Environment inventory</h2><span>Inventory every 15 seconds · build and operation progress every 5 seconds</span></div><button className="refresh-button" aria-label="Refresh environments" disabled={environments.isFetching} onClick={() => { void environments.refetch(); void platform.refetch(); if (canTrack) void operations.refetch(); if (canTrackBuilds) void builds.refetch(); }}><RefreshCw size={16} className={environments.isFetching ? 'spin' : ''} /></button></div>
            {environments.isSuccess && items.length === 0 ? <div className="empty-state"><div className="empty-illustration"><GitBranch size={29} /><span><Box size={17} /></span></div><h3>Your next idea starts here.</h3><p>No environment requests yet.<br />Use Spawn environment to save your first branch request.</p><div className="empty-check"><Check size={14} />Database connected · no sample environments</div></div>
              : items.length ? <div className="environment-list">{items.map(env => <EnvironmentCard key={env.id} environment={env} canManage={canManage} operation={progress.get(env.id)} trackProgress={canTrack} build={buildProgress.get(env.id)} provisioning={provisioningProgress.get(env.id)} />)}</div>
              : <div className="empty-state"><CircleDot size={30} /><h3>{environments.isError ? 'Inventory is unavailable' : 'Loading your workspace…'}</h3><p>{environments.isError ? 'Start the local services, then refresh.' : 'Checking the platform database.'}</p></div>}
          </section>

          <section className="next-step"><div><p className="eyebrow">WHAT COMES NEXT</p><h2>A private place to test.</h2><p>Add authenticated preview routing and endpoint checks before reporting an environment ready.</p></div><div className="flow"><span><Database size={17} />Intent</span><ChevronRight size={15} /><span><GitBranch size={17} />Workloads</span><ChevronRight size={15} /><span><Box size={17} />Private preview<ArrowUpRight size={13} /></span></div></section>
          <footer>KubeSpawn developer platform<span>Designed for short-lived environments. Built for deliberate delivery.</span></footer>
        </main>
      </div>
      <SpawnDialog open={spawnOpen} onClose={() => setSpawnOpen(false)} onCreated={result => {
        setNotice(`Request saved for ${result.environment.repository}. Waiting for build; no preview is deployed yet.`);
        void client.invalidateQueries({ queryKey: ['environments'] });
        void client.invalidateQueries({ queryKey: ['operations'] });
        void client.invalidateQueries({ queryKey: ['github-builds'] });
      }} />
    </div>
  );
}
