import { useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Activity, Building2, CircleAlert, ShieldCheck, UserPlus, Users,
  Layers, Wrench, FileText, Cpu, CreditCard
} from 'lucide-react';
import { getJson, provisionUser, updateMembership, type AdminOverview, type AdminUser, type Membership, type Profile } from './api';

import { ProjectRepositoriesTab } from './components/ProjectRepositoriesTab';
import { AdminQuotasTab } from './components/AdminQuotasTab';
import { AdminUsageTab } from './components/AdminUsageTab';
import { AdminInventoryTab } from './components/AdminInventoryTab';
import { AdminRecoveryTab } from './components/AdminRecoveryTab';
import { AdminAuditTab } from './components/AdminAuditTab';
import { AdminHealthTab } from './components/AdminHealthTab';

const roles: Membership['role'][] = ['DEVELOPER', 'QA_REVIEWER', 'PORTAL_ADMIN'];

type AdminTab = 'users' | 'repositories' | 'quotas' | 'usage' | 'inventory' | 'recovery' | 'audit' | 'health';

export function AdminView({ profile }: { profile: Profile }) {
  const client = useQueryClient();
  const overview = useQuery({ queryKey: ['admin-overview'], queryFn: () => getJson<AdminOverview>('/api/v1/admin/overview') });
  const users = useQuery({ queryKey: ['admin-users'], queryFn: () => getJson<AdminUser[]>('/api/v1/admin/users') });
  const [showAdd, setShowAdd] = useState(false);
  const [activeTab, setActiveTab] = useState<AdminTab>('users');
  const [preselectedRecoveryEnvId, setPreselectedRecoveryEnvId] = useState<string | null>(null);

  // Active tenant
  const [selectedTenantId, setSelectedTenantId] = useState<string>(
    profile.memberships[0]?.tenantId || '10000000-0000-0000-0000-000000000001'
  );

  const defaultTenantKey = profile.memberships.find(m => m.tenantId === selectedTenantId)?.tenantKey
    ?? profile.memberships[0]?.tenantKey ?? 'local';

  return (
    <div className="saas-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">PLATFORM ADMINISTRATION</p>
          <h1>Admin center</h1>
          <p>Administer organization quotas, concurrency ceilings, resource allocations, inventory, and recoveries.</p>
        </div>
        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          {profile.memberships.length > 1 && (
            <select
              value={selectedTenantId}
              onChange={(e) => setSelectedTenantId(e.target.value)}
              style={{ background: '#1e293b', color: '#f8fafc', border: '1px solid #334155', borderRadius: '6px', padding: '0.45rem 0.8rem', fontSize: '0.85rem' }}
            >
              {profile.memberships.map((m) => (
                <option key={m.tenantId} value={m.tenantId}>
                  {m.tenantName || m.tenantKey} ({m.role})
                </option>
              ))}
            </select>
          )}

          {activeTab === 'users' && (
            <button className="spawn-button" onClick={() => setShowAdd(!showAdd)}>
              <UserPlus size={17} /> Add identity
            </button>
          )}
        </div>
      </div>

      {overview.error && <div className="error-banner">{overview.error.message}</div>}

      <section className="admin-summary">
        <Summary icon={<Users size={17} />} label="Active users" value={overview.data?.activeUsers} detail={`${overview.data?.users ?? '—'} total`} />
        <Summary icon={<Building2 size={17} />} label="Tenants" value={overview.data?.tenants} detail="Active workspaces" />
        <Summary icon={<Activity size={17} />} label="Active environments" value={overview.data?.activeEnvironments} detail={`${overview.data?.queuedEnvironments ?? '—'} queued`} />
        <Summary icon={<CircleAlert size={17} />} label="Needs attention" value={overview.data?.failedEnvironments} detail="Failed or degraded" />
      </section>

      {/* Admin Tabs */}
      <div className="profile-tabs" style={{ marginBottom: '1.5rem', flexWrap: 'wrap', gap: '0.4rem' }}>
        <button
          className={`profile-tab ${activeTab === 'users' ? 'active' : ''}`}
          onClick={() => setActiveTab('users')}
        >
          <Users size={16} /> User Directory
        </button>
        <button
          className={`profile-tab ${activeTab === 'repositories' ? 'active' : ''}`}
          onClick={() => setActiveTab('repositories')}
        >
          <ShieldCheck size={16} /> Repositories &amp; Policies
        </button>
        <button
          className={`profile-tab ${activeTab === 'quotas' ? 'active' : ''}`}
          onClick={() => setActiveTab('quotas')}
        >
          <Cpu size={16} /> Quotas &amp; Ceilings
        </button>
        <button
          className={`profile-tab ${activeTab === 'usage' ? 'active' : ''}`}
          onClick={() => setActiveTab('usage')}
        >
          <CreditCard size={16} /> Plans &amp; Usage
        </button>
        <button
          className={`profile-tab ${activeTab === 'inventory' ? 'active' : ''}`}
          onClick={() => setActiveTab('inventory')}
        >
          <Layers size={16} /> Environment Inventory
        </button>
        <button
          className={`profile-tab ${activeTab === 'recovery' ? 'active' : ''}`}
          onClick={() => setActiveTab('recovery')}
        >
          <Wrench size={16} /> Operations &amp; Recovery
        </button>
        <button
          className={`profile-tab ${activeTab === 'audit' ? 'active' : ''}`}
          onClick={() => setActiveTab('audit')}
        >
          <FileText size={16} /> Audit History
        </button>
        <button
          className={`profile-tab ${activeTab === 'health' ? 'active' : ''}`}
          onClick={() => setActiveTab('health')}
        >
          <Activity size={16} /> Integration Health
        </button>
      </div>

      {activeTab === 'users' && (
        <>
          {showAdd && (
            <ProvisionForm
              defaultTenant={defaultTenantKey}
              onDone={() => {
                setShowAdd(false);
                void client.invalidateQueries({ queryKey: ['admin-users'] });
                void client.invalidateQueries({ queryKey: ['admin-overview'] });
              }}
            />
          )}
          <section className="settings-card user-directory">
            <div className="settings-card-heading">
              <ShieldCheck size={22} />
              <div>
                <h2>User directory</h2>
                <p>Only provisioned external identities may enter the platform.</p>
              </div>
            </div>
            {users.isPending ? (
              <p className="muted">Loading users…</p>
            ) : users.error ? (
              <p className="inline-error">{users.error.message}</p>
            ) : (
              <div className="user-table">
                {users.data?.map((user) => (
                  <UserRow key={user.id} user={user} />
                ))}
              </div>
            )}
          </section>
        </>
      )}

      {activeTab === 'repositories' && <ProjectRepositoriesTab />}

      {activeTab === 'quotas' && <AdminQuotasTab tenantId={selectedTenantId} />}

      {activeTab === 'usage' && <AdminUsageTab tenantId={selectedTenantId} />}

      {activeTab === 'inventory' && (
        <AdminInventoryTab
          tenantId={selectedTenantId}
          onSelectForRecovery={(envId) => {
            setPreselectedRecoveryEnvId(envId);
            setActiveTab('recovery');
          }}
        />
      )}

      {activeTab === 'recovery' && (
        <AdminRecoveryTab
          tenantId={selectedTenantId}
          preselectedEnvId={preselectedRecoveryEnvId}
          onClearPreselected={() => setPreselectedRecoveryEnvId(null)}
        />
      )}

      {activeTab === 'audit' && <AdminAuditTab tenantId={selectedTenantId} />}

      {activeTab === 'health' && <AdminHealthTab tenantId={selectedTenantId} />}
    </div>
  );
}

function Summary({ icon, label, value, detail }: { icon: ReactNode; label: string; value?: number; detail: string }) {
  return (
    <article>
      {icon}
      <span>
        {label}
        <strong>{value ?? '—'}</strong>
        <small>{detail}</small>
      </span>
    </article>
  );
}

function ProvisionForm({ defaultTenant, onDone }: { defaultTenant: string; onDone: () => void }) {
  const [form, setForm] = useState({
    issuer: '',
    subject: '',
    email: '',
    displayName: '',
    tenantKey: defaultTenant,
    role: 'DEVELOPER' as Membership['role'],
    platformAdmin: false,
  });
  const mutation = useMutation({ mutationFn: () => provisionUser(form), onSuccess: onDone });
  return (
    <section className="settings-card provision-form">
      <div className="settings-card-heading">
        <UserPlus size={22} />
        <div>
          <h2>Provision an identity</h2>
          <p>Match the issuer and subject claims from your OIDC provider.</p>
        </div>
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <label>
          OIDC issuer
          <input required value={form.issuer} onChange={(e) => setForm({ ...form, issuer: e.target.value })} placeholder="https://id.example.com" />
        </label>
        <label>
          Subject
          <input required value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} placeholder="Provider subject identifier" />
        </label>
        <label>
          Display name
          <input required value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        </label>
        <label>
          Email
          <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </label>
        <label>
          Tenant key
          <input required value={form.tenantKey} onChange={(e) => setForm({ ...form, tenantKey: e.target.value })} />
        </label>
        <label>
          Role
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Membership['role'] })}>
            {roles.map((role) => (
              <option key={role}>{role}</option>
            ))}
          </select>
        </label>
        <label className="checkbox-field">
          <input type="checkbox" checked={form.platformAdmin} onChange={(e) => setForm({ ...form, platformAdmin: e.target.checked })} />
          Platform administrator
        </label>
        {mutation.error && <p className="inline-error">{mutation.error.message}</p>}
        <button className="primary-button" disabled={mutation.isPending} type="submit">
          Provision user
        </button>
      </form>
    </section>
  );
}

function UserRow({ user }: { user: AdminUser }) {
  const client = useQueryClient();
  const membership = user.memberships[0];
  const [role, setRole] = useState(membership?.role ?? 'DEVELOPER');
  const [status, setStatus] = useState(membership?.status ?? 'ACTIVE');
  const mutation = useMutation({
    mutationFn: () => updateMembership(membership!.tenantId, user.id, role, status),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['admin-users'] });
      void client.invalidateQueries({ queryKey: ['admin-overview'] });
    },
  });

  return (
    <article className="user-row">
      <div>
        <h3>{user.displayName}</h3>
        <p>{user.email ?? 'No email'}</p>
        <small>{membership ? `${membership.tenantKey} · ${membership.role}` : 'No memberships'}</small>
      </div>
      {membership ? (
        <form
          className="user-row-controls"
          onSubmit={(event) => {
            event.preventDefault();
            mutation.mutate();
          }}
        >
          <select value={role} onChange={(e) => setRole(e.target.value as Membership['role'])}>
            {roles.map((item) => (
              <option key={item}>{item}</option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as Membership['status'])}>
            <option value="ACTIVE">ACTIVE</option>
            <option value="SUSPENDED">SUSPENDED</option>
          </select>
          <button className="ghost-button" disabled={mutation.isPending} type="submit">
            Save
          </button>
        </form>
      ) : (
        <span className="muted">Pending onboarding</span>
      )}
    </article>
  );
}
