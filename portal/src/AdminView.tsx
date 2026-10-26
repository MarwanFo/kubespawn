import { useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Building2, CircleAlert, ShieldCheck, UserPlus, Users } from 'lucide-react';
import { getJson, provisionUser, updateMembership, type AdminOverview, type AdminUser, type Membership, type Profile } from './api';

const roles: Membership['role'][] = ['VIEWER', 'DEVELOPER', 'MAINTAINER', 'TENANT_ADMIN'];

export function AdminView({ profile }: { profile: Profile }) {
  const client = useQueryClient();
  const overview = useQuery({ queryKey: ['admin-overview'], queryFn: () => getJson<AdminOverview>('/api/v1/admin/overview') });
  const users = useQuery({ queryKey: ['admin-users'], queryFn: () => getJson<AdminUser[]>('/api/v1/admin/users') });
  const [showAdd, setShowAdd] = useState(false);
  const defaultTenant = profile.memberships[0]?.tenantKey ?? 'local';

  return <div className="saas-page">
    <div className="page-heading"><div><p className="eyebrow">PLATFORM ADMINISTRATION</p><h1>Admin center</h1>
      <p>Manage identities, tenant access, and platform health.</p></div><button className="spawn-button" onClick={() => setShowAdd(!showAdd)}><UserPlus size={17} />Add identity</button></div>
    {overview.error && <div className="error-banner">{overview.error.message}</div>}
    <section className="admin-summary">
      <Summary icon={<Users size={17} />} label="Active users" value={overview.data?.activeUsers} detail={`${overview.data?.users ?? '—'} total`} />
      <Summary icon={<Building2 size={17} />} label="Tenants" value={overview.data?.tenants} detail="Active workspaces" />
      <Summary icon={<Activity size={17} />} label="Active environments" value={overview.data?.activeEnvironments} detail={`${overview.data?.queuedEnvironments ?? '—'} queued`} />
      <Summary icon={<CircleAlert size={17} />} label="Needs attention" value={overview.data?.failedEnvironments} detail="Failed or degraded" />
    </section>
    {showAdd && <ProvisionForm defaultTenant={defaultTenant} onDone={() => { setShowAdd(false); void client.invalidateQueries({ queryKey: ['admin-users'] }); void client.invalidateQueries({ queryKey: ['admin-overview'] }); }} />}
    <section className="settings-card user-directory"><div className="settings-card-heading"><ShieldCheck size={22} /><div><h2>User directory</h2><p>Only provisioned external identities may enter the platform.</p></div></div>
      {users.isPending ? <p className="muted">Loading users…</p> : users.error ? <p className="inline-error">{users.error.message}</p>
        : <div className="user-table">{users.data?.map(user => <UserRow key={user.id} user={user} />)}</div>}
    </section>
  </div>;
}

function Summary({ icon, label, value, detail }: { icon: ReactNode; label: string; value?: number; detail: string }) {
  return <article>{icon}<span>{label}<strong>{value ?? '—'}</strong><small>{detail}</small></span></article>;
}

function ProvisionForm({ defaultTenant, onDone }: { defaultTenant: string; onDone: () => void }) {
  const [form, setForm] = useState({ issuer: '', subject: '', email: '', displayName: '', tenantKey: defaultTenant,
    role: 'DEVELOPER' as Membership['role'], platformAdmin: false });
  const mutation = useMutation({ mutationFn: () => provisionUser(form), onSuccess: onDone });
  return <section className="settings-card provision-form"><div className="settings-card-heading"><UserPlus size={22} /><div><h2>Provision an identity</h2><p>Match the issuer and subject claims from your OIDC provider.</p></div></div>
    <form onSubmit={event => { event.preventDefault(); mutation.mutate(); }}>
      <label>OIDC issuer<input required value={form.issuer} onChange={e => setForm({ ...form, issuer: e.target.value })} placeholder="https://id.example.com" /></label>
      <label>Subject<input required value={form.subject} onChange={e => setForm({ ...form, subject: e.target.value })} placeholder="Provider subject identifier" /></label>
      <label>Display name<input required value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} /></label>
      <label>Email<input type="email" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
      <label>Tenant key<input required value={form.tenantKey} onChange={e => setForm({ ...form, tenantKey: e.target.value })} /></label>
      <label>Role<select value={form.role} onChange={e => setForm({ ...form, role: e.target.value as Membership['role'] })}>{roles.map(role => <option key={role}>{role}</option>)}</select></label>
      <label className="checkbox-field"><input type="checkbox" checked={form.platformAdmin} onChange={e => setForm({ ...form, platformAdmin: e.target.checked })} />Platform administrator</label>
      {mutation.error && <p className="inline-error">{mutation.error.message}</p>}
      <button className="primary-button" disabled={mutation.isPending} type="submit">Provision user</button>
    </form>
  </section>;
}

function UserRow({ user }: { user: AdminUser }) {
  const client = useQueryClient();
  const membership = user.memberships[0];
  const [role, setRole] = useState<Membership['role']>(membership?.role ?? 'VIEWER');
  const [status, setStatus] = useState<Membership['status']>(membership?.status ?? 'ACTIVE');
  const save = useMutation({ mutationFn: () => membership
    ? updateMembership(membership.tenantId, user.id, role, status)
    : Promise.reject(new Error('This user has no tenant membership.')), onSuccess: () => void client.invalidateQueries({ queryKey: ['admin-users'] }) });
  return <article className="user-row"><span className="directory-avatar">{user.displayName.slice(0, 2).toUpperCase()}</span><div className="user-copy"><strong>{user.displayName}{user.platformAdmin && <em>Platform admin</em>}</strong><small>{user.email ?? 'No email'} · Last active {user.lastSeenAt ? new Date(user.lastSeenAt).toLocaleString() : 'never'}</small></div>
    {membership && <><select aria-label={`Role for ${user.displayName}`} value={role} onChange={e => setRole(e.target.value as Membership['role'])}>{roles.map(value => <option key={value}>{value}</option>)}</select>
      <select aria-label={`Status for ${user.displayName}`} value={status} onChange={e => setStatus(e.target.value as Membership['status'])}><option>ACTIVE</option><option>SUSPENDED</option></select>
      <button className="small-button" disabled={save.isPending || (role === membership.role && status === membership.status)} onClick={() => save.mutate()}>Save</button></>}
  </article>;
}
