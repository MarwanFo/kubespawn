import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, ArrowUpRight, Box, Check, ChevronRight, CircleDot, Database, GitBranch, Layers3, LogOut, RefreshCw, Server, ShieldCheck, Terminal, UserRound, Users } from 'lucide-react';
import { ApiError, getJson, logout } from './api';
import type { BuildProgress, EnvironmentPage, Operation, PlatformStatus, Profile, ProvisioningProgress } from './api';
import { SpawnDialog } from './SpawnDialog';
import { EnvironmentCard } from './EnvironmentCard';
import { ProfileView } from './ProfileView';
import { AdminView } from './AdminView';

type View = 'environments' | 'profile' | 'admin';

export function App() {
  const client = useQueryClient();
  const [view, setView] = useState<View>('environments');
  const [spawnOpen, setSpawnOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const me = useQuery({ queryKey: ['me'], queryFn: () => getJson<Profile>('/api/v1/me'), retry: false });
  const authenticated = me.isSuccess;
  const platform = useQuery({ queryKey: ['platform'], queryFn: () => getJson<PlatformStatus>('/api/v1/platform'), enabled: authenticated });
  const environments = useQuery({ queryKey: ['environments'], queryFn: () => getJson<EnvironmentPage>('/api/v1/environments'), enabled: authenticated });
  const items = environments.data?.items ?? [];
  const connected = authenticated && platform.isSuccess && environments.isSuccess;
  const error = platform.error || environments.error;
  const canManage = platform.data?.capabilities.includes('lifecycle-intents') ?? false;
  const canTrack = platform.data?.capabilities.includes('job-processing') ?? false;
  const operations = useQuery({ queryKey: ['operations'], queryFn: () => getJson<Operation[]>('/api/v1/operations'),
    enabled: authenticated && canTrack, refetchInterval: 5_000 });
  const progress = new Map(operations.data?.map(operation => [operation.environmentId, operation]));
  const canTrackBuilds = platform.data?.capabilities.includes('github-builds') ?? false;
  const builds = useQuery({ queryKey: ['github-builds'], queryFn: () => getJson<BuildProgress[]>('/api/v1/github/environment-builds'),
    enabled: authenticated && canTrackBuilds, refetchInterval: 5_000 });
  const buildProgress = new Map(builds.data?.map(build => [build.environmentId, build]));
  const canTrackProvisioning = platform.data?.capabilities.includes('kubernetes-provisioning') ?? false;
  const provisioning = useQuery({ queryKey: ['provisioning'], queryFn: () => getJson<ProvisioningProgress[]>('/api/v1/kubernetes/environment-progress'),
    enabled: authenticated && canTrackProvisioning, refetchInterval: 5_000 });
  const provisioningProgress = new Map(provisioning.data?.map(value => [value.environmentId, value]));

  if (me.isPending) return <div className="auth-screen"><Layers3 size={30} /><h1>KubeSpawn</h1><p>Loading your account…</p></div>;
  if (me.isError) return <SignIn error={me.error} />;
  const profile = me.data;
  const workspace = profile.memberships[0];

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <button className="brand brand-button" onClick={() => setView('environments')} aria-label="KubeSpawn home"><span className="brand-mark"><Layers3 size={23} /></span>KubeSpawn<span className="brand-dot">.</span></button>
        <div className="workspace"><span className="workspace-avatar">{workspace?.tenantName.slice(0, 2).toUpperCase() ?? 'KS'}</span><div>{workspace?.tenantName ?? 'No workspace'}<small>{workspace?.role.replace('_', ' ') ?? 'No membership'}</small></div><ChevronRight size={15} /></div>
        <div className="nav-label">WORKSPACE</div>
        <button className={`nav-item ${view === 'environments' ? 'selected' : ''}`} onClick={() => setView('environments')}><Box size={18} />Environments<span className="nav-count">{environments.isSuccess ? items.length : '—'}</span></button>
        <button className={`nav-item ${view === 'profile' ? 'selected' : ''}`} onClick={() => setView('profile')}><UserRound size={18} />Profile</button>
        {profile.platformAdmin && <button className={`nav-item ${view === 'admin' ? 'selected' : ''}`} onClick={() => setView('admin')}><Users size={18} />Admin</button>}
        <div className="sidebar-note"><ShieldCheck size={17} /><span>Identity protected<small>{profile.authMode === 'local' ? 'Local bootstrap administrator' : 'Organization OIDC session'}</small></span></div>
        <div className="sidebar-footer"><span className={`status-dot ${connected ? 'online' : ''}`} />{connected ? 'API connected' : 'Connecting to API'}<small>SaaS foundation · v{platform.data?.version ?? '0.7.0'}</small></div>
      </aside>

      <div className="main-area">
        <header className="topbar"><span>Workspace <ChevronRight size={13} /> <strong>{view === 'environments' ? 'Environments' : view === 'profile' ? 'Profile' : 'Admin center'}</strong></span><div className="account-menu"><button onClick={() => setView('profile')}><span className="mini-avatar">{profile.displayName.slice(0, 2).toUpperCase()}</span><span>{profile.displayName}<small>{profile.platformAdmin ? 'Platform admin' : workspace?.role.replace('_', ' ')}</small></span></button>{profile.authMode === 'oidc' && <button className="logout-button" aria-label="Sign out" onClick={() => void logout()}><LogOut size={16} /></button>}</div></header>
        <main>
          {view === 'profile' ? <ProfileView profile={profile} /> : view === 'admin' && profile.platformAdmin ? <AdminView profile={profile} /> : <>
          <div className="page-heading"><div><p className="eyebrow">YOUR DEVELOPER WORKSPACE</p><h1>Environments</h1><p>From pull request to a place you can test.</p></div><button className="spawn-button" disabled={!connected || !canManage} onClick={() => setSpawnOpen(true)} title={canManage ? 'Create an environment request' : 'Lifecycle requests require the updated API in local mode'}><Box size={17} />Spawn environment</button></div>

          <section className="foundation-banner"><span className="banner-icon"><Terminal size={21} /></span><div><strong>{platform.data?.controllerEnabled ? 'Verified builds. Private previews.' : platform.data?.githubConfigured ? 'Approved builds. Exact source revisions.' : 'Connect GitHub to verify builds.'}</strong><p>{platform.data?.controllerEnabled ? 'An environment becomes ready only after its private route accepts an authenticated edge probe and denies anonymous access.' : 'Connect GitHub and enable the local Kubernetes integration to provision approved builds.'} <a href="https://github.com/MarwanFo/kubespawn/blob/main/docs/preview-routing.md" target="_blank" rel="noreferrer">Setup guide ↗</a></p></div><span className="phase-tag">PHASE 07</span></section>

          {error && <div role="alert" className="error-banner">{error.message}</div>}
          {canTrack && operations.error && <div role="alert" className="error-banner">Operation progress is unavailable. {operations.error.message}</div>}
          {canTrackBuilds && builds.error && <div role="alert" className="error-banner">Build progress is unavailable. {builds.error.message}</div>}
          {canTrackProvisioning && provisioning.error && <div role="alert" className="error-banner">Provisioning progress is unavailable. {provisioning.error.message}</div>}
          {notice && <div role="status" className="success-banner">{notice}<button onClick={() => setNotice('')} aria-label="Dismiss notification">Dismiss</button></div>}

          <section className="summary-grid" aria-label="Platform overview">
            <article className="summary-card"><span>Recent requests<Box size={17} /></span><strong>{environments.isSuccess ? items.length : '—'}</strong><small>{environments.isSuccess ? 'Visible to your authorized workspaces' : 'Waiting for database response'}</small></article>
            <article className="summary-card"><span>Platform API<Activity size={17} /></span><strong className="text-value">{platform.isSuccess ? 'Connected' : platform.isError ? 'Unavailable' : 'Connecting'}</strong><small>{platform.data ? `Spring Boot · ${platform.data.mode}` : 'Checking the control plane'}</small></article>
            <article className="summary-card"><span>Job worker<Server size={17} /></span><strong className="text-value">{platform.data ? platform.data.workerEnabled ? 'Enabled' : 'Disabled' : 'Checking'}</strong><small>Build and cleanup prerequisites are checked automatically</small></article>
          </section>

          <section className="environment-panel" id="environments">
            <div className="panel-header"><div><h2>Environment inventory</h2><span>Tenant-authorized inventory · build and operation progress every 5 seconds</span></div><button className="refresh-button" aria-label="Refresh environments" disabled={environments.isFetching} onClick={() => { void environments.refetch(); void platform.refetch(); if (canTrack) void operations.refetch(); if (canTrackBuilds) void builds.refetch(); }}><RefreshCw size={16} className={environments.isFetching ? 'spin' : ''} /></button></div>
            {environments.isSuccess && items.length === 0 ? <div className="empty-state"><div className="empty-illustration"><GitBranch size={29} /><span><Box size={17} /></span></div><h3>Your next idea starts here.</h3><p>No environment requests yet.<br />Use Spawn environment to save your first branch request.</p><div className="empty-check"><Check size={14} />Database connected · no sample environments</div></div>
              : items.length ? <div className="environment-list">{items.map(env => <EnvironmentCard key={env.id} environment={env} canManage={canManage} operation={progress.get(env.id)} trackProgress={canTrack} build={buildProgress.get(env.id)} provisioning={provisioningProgress.get(env.id)} />)}</div>
              : <div className="empty-state"><CircleDot size={30} /><h3>{environments.isError ? 'Inventory is unavailable' : 'Loading your workspace…'}</h3><p>{environments.isError ? 'Start the local services, then refresh.' : 'Checking the platform database.'}</p></div>}
          </section>

          <section className="next-step"><div><p className="eyebrow">SAAS FOUNDATION</p><h2>Identity before deployment.</h2><p>Persisted users, tenant memberships, profiles, and platform administration now precede production infrastructure.</p></div><div className="flow"><span><UserRound size={17} />Identity</span><ChevronRight size={15} /><span><Database size={17} />Tenant policy</span><ChevronRight size={15} /><span><Box size={17} />Private preview<ArrowUpRight size={13} /></span></div></section>
          </>}
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

function SignIn({ error }: { error: Error }) {
  const unauthorized = error instanceof ApiError && error.status === 401;
  return <div className="auth-screen"><span className="auth-logo"><Layers3 size={27} /></span><p className="eyebrow">KUBESPAWN</p><h1>{unauthorized ? 'Sign in to your workspace' : 'Account unavailable'}</h1>
    <p>{unauthorized ? 'Use your organization identity to access authorized repositories and private previews.' : error.message}</p>
    {unauthorized && <a className="primary-button" href="/oauth2/authorization/kubespawn">Continue with SSO</a>}
    <small>Access is invite-only. Contact a platform administrator if your identity has not been provisioned.</small></div>;
}
