import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Building2, Check, UserRound, Mail, UserPlus,
  Trash2, Key, Clock, Laptop, ExternalLink, AlertTriangle, Copy, CheckCheck
} from 'lucide-react';
import {
  updateProfile, getMembers, updateMemberRole, updateMemberStatus, removeMember,
  getInvitations, createInvitation, revokeInvitation, getSessions, revokeOtherSessions,
  type Profile, type TenantMember
} from './api';
import { useWorkspace } from './context/WorkspaceContext';

export function ProfileView({ profile }: { profile: Profile }) {
  const { activeTenant, permissions } = useWorkspace();
  const [activeTab, setActiveTab] = useState<'profile' | 'members' | 'invitations' | 'sessions'>('profile');

  return (
    <div className="saas-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">ACCOUNT &amp; WORKSPACE</p>
          <h1>Settings &amp; Administration</h1>
          <p>Manage your account profile, workspace members, invitations, and active sessions.</p>
        </div>
      </div>

      {/* Tabs navigation */}
      <div className="settings-tabs" role="tablist">
        <button
          role="tab"
          aria-selected={activeTab === 'profile'}
          className={`tab-button ${activeTab === 'profile' ? 'active' : ''}`}
          onClick={() => setActiveTab('profile')}
        >
          <UserRound size={16} />
          Profile &amp; Account
        </button>

        <button
          role="tab"
          aria-selected={activeTab === 'members'}
          className={`tab-button ${activeTab === 'members' ? 'active' : ''}`}
          onClick={() => setActiveTab('members')}
        >
          <Building2 size={16} />
          Members ({activeTenant?.tenantName || 'Workspace'})
        </button>

        {permissions?.canManageOrganization && (
          <button
            role="tab"
            aria-selected={activeTab === 'invitations'}
            className={`tab-button ${activeTab === 'invitations' ? 'active' : ''}`}
            onClick={() => setActiveTab('invitations')}
          >
            <UserPlus size={16} />
            Invitations
          </button>
        )}

        <button
          role="tab"
          aria-selected={activeTab === 'sessions'}
          className={`tab-button ${activeTab === 'sessions' ? 'active' : ''}`}
          onClick={() => setActiveTab('sessions')}
        >
          <Laptop size={16} />
          Active Sessions
        </button>
      </div>

      {/* Tab Panels */}
      {activeTab === 'profile' && <ProfileTab profile={profile} />}
      {activeTab === 'members' && activeTenant && (
        <MembersTab tenantId={activeTenant.tenantId} canManage={Boolean(permissions?.canManageOrganization)} />
      )}
      {activeTab === 'invitations' && activeTenant && (
        <InvitationsTab tenantId={activeTenant.tenantId} />
      )}
      {activeTab === 'sessions' && <SessionsTab />}
    </div>
  );
}

function ProfileTab({ profile }: { profile: Profile }) {
  const client = useQueryClient();
  const [displayName, setDisplayName] = useState(profile.displayName);
  const [avatarUrl, setAvatarUrl] = useState(profile.avatarUrl ?? '');

  useEffect(() => {
    setDisplayName(profile.displayName);
    setAvatarUrl(profile.avatarUrl ?? '');
  }, [profile]);

  const save = useMutation({
    mutationFn: () => updateProfile(displayName, avatarUrl),
    onSuccess: (value) => {
      client.setQueryData(['me'], value);
    },
  });

  return (
    <div className="profile-layout">
      <section className="settings-card">
        <div className="settings-card-heading">
          <span className="profile-avatar">
            {profile.avatarUrl ? <img src={profile.avatarUrl} alt="" /> : <UserRound size={24} />}
          </span>
          <div>
            <h2>Personal details</h2>
            <p>These details are displayed to teammates across your workspaces.</p>
          </div>
        </div>
        <form onSubmit={(event) => { event.preventDefault(); save.mutate(); }}>
          <label>
            Display name
            <input
              required
              maxLength={120}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </label>
          <label>
            Email
            <input
              value={profile.email ?? 'Managed by your identity provider'}
              disabled
              readOnly
            />
          </label>
          <label>
            Avatar URL
            <input
              type="url"
              maxLength={2048}
              value={avatarUrl}
              onChange={(event) => setAvatarUrl(event.target.value)}
              placeholder="https://…"
            />
          </label>
          {save.error && <p role="alert" className="inline-error">{save.error.message}</p>}
          {save.isSuccess && <p role="status" className="inline-success"><Check size={13} />Profile saved</p>}
          <button className="primary-button" disabled={save.isPending || !displayName.trim()} type="submit">
            {save.isPending ? 'Saving…' : 'Save profile'}
          </button>
        </form>
      </section>

      <section className="settings-card">
        <div className="settings-card-heading">
          <Key size={22} />
          <div>
            <h2>Security &amp; Credentials</h2>
            <p>Manage your password and Multi-Factor Authentication.</p>
          </div>
        </div>
        <div className="security-notice">
          <p>
            Your account is secured via enterprise OIDC identity federation. You can update your password,
            manage two-factor authentication devices, and view recent security audits.
          </p>
          <a
            href="http://localhost:8081/realms/kubespawn/account"
            target="_blank"
            rel="noopener noreferrer"
            className="secondary-button"
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', marginTop: '1rem' }}
          >
            <ExternalLink size={16} />
            Manage Keycloak Account &amp; MFA
          </a>
        </div>
      </section>
    </div>
  );
}

function MembersTab({ tenantId, canManage }: { tenantId: string; canManage: boolean }) {
  const client = useQueryClient();
  const members = useQuery({
    queryKey: ['tenant-members', tenantId],
    queryFn: () => getMembers(tenantId),
  });

  const [actionError, setActionError] = useState<string | null>(null);

  const roleMutation = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: TenantMember['role'] }) =>
      updateMemberRole(tenantId, userId, role),
    onSuccess: () => {
      setActionError(null);
      void client.invalidateQueries({ queryKey: ['tenant-members', tenantId] });
    },
    onError: (err: Error) => setActionError(err.message),
  });

  const statusMutation = useMutation({
    mutationFn: ({ userId, status }: { userId: string; status: TenantMember['status'] }) =>
      updateMemberStatus(tenantId, userId, status),
    onSuccess: () => {
      setActionError(null);
      void client.invalidateQueries({ queryKey: ['tenant-members', tenantId] });
    },
    onError: (err: Error) => setActionError(err.message),
  });

  const removeMutation = useMutation({
    mutationFn: (userId: string) => removeMember(tenantId, userId),
    onSuccess: () => {
      setActionError(null);
      void client.invalidateQueries({ queryKey: ['tenant-members', tenantId] });
    },
    onError: (err: Error) => setActionError(err.message),
  });

  return (
    <section className="settings-card full-width">
      <div className="settings-card-heading">
        <Building2 size={22} />
        <div>
          <h2>Organization Members</h2>
          <p>Members who have access to projects and environments in this organization.</p>
        </div>
      </div>

      {actionError && (
        <div className="error-banner" style={{ marginBottom: '1.25rem' }}>
          <AlertTriangle size={18} />
          <span>{actionError}</span>
        </div>
      )}

      {members.isPending ? (
        <p className="muted">Loading organization members…</p>
      ) : members.isError ? (
        <p className="inline-error">{members.error.message}</p>
      ) : (
        <div className="members-table">
          <div className="members-table-header">
            <span>Member</span>
            <span>Email</span>
            <span>Role</span>
            <span>Status</span>
            <span>Joined</span>
            {canManage && <span>Actions</span>}
          </div>
          {members.data.map((member) => (
            <div key={member.userId} className={`member-row ${member.status === 'SUSPENDED' ? 'suspended' : ''}`}>
              <div className="member-user-cell">
                <span className="directory-avatar">
                  {member.displayName.slice(0, 2).toUpperCase()}
                </span>
                <div>
                  <strong>{member.displayName}</strong>
                  {member.role === 'PORTAL_ADMIN' && <span className="admin-chip">Admin</span>}
                </div>
              </div>
              <span className="member-email">{member.email || '—'}</span>
              <div className="member-role-cell">
                {canManage ? (
                  <select
                    value={member.role}
                    disabled={roleMutation.isPending}
                    onChange={(e) =>
                      roleMutation.mutate({ userId: member.userId, role: e.target.value as TenantMember['role'] })
                    }
                  >
                    <option value="DEVELOPER">DEVELOPER</option>
                    <option value="QA_REVIEWER">QA_REVIEWER</option>
                    <option value="PORTAL_ADMIN">PORTAL_ADMIN</option>
                  </select>
                ) : (
                  <span className={`tenant-role-pill ${member.role.toLowerCase()}`}>{member.role}</span>
                )}
              </div>
              <div className="member-status-cell">
                {canManage ? (
                  <button
                    className={`status-toggle-button ${member.status.toLowerCase()}`}
                    disabled={statusMutation.isPending}
                    onClick={() =>
                      statusMutation.mutate({
                        userId: member.userId,
                        status: member.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                      })
                    }
                  >
                    {member.status}
                  </button>
                ) : (
                  <span className={`status-pill ${member.status.toLowerCase()}`}>{member.status}</span>
                )}
              </div>
              <span className="member-date">{new Date(member.joinedAt).toLocaleDateString()}</span>
              {canManage && (
                <div className="member-actions-cell">
                  <button
                    className="danger-button-subtle"
                    title="Remove member from organization"
                    disabled={removeMutation.isPending}
                    onClick={() => {
                      if (confirm(`Are you sure you want to remove ${member.displayName} from this organization?`)) {
                        removeMutation.mutate(member.userId);
                      }
                    }}
                  >
                    <Trash2 size={15} />
                    Remove
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function InvitationsTab({ tenantId }: { tenantId: string }) {
  const client = useQueryClient();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<'DEVELOPER' | 'QA_REVIEWER' | 'PORTAL_ADMIN'>('DEVELOPER');
  const [lastCreatedToken, setLastCreatedToken] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const invitations = useQuery({
    queryKey: ['tenant-invitations', tenantId],
    queryFn: () => getInvitations(tenantId),
  });

  const inviteMutation = useMutation({
    mutationFn: () => createInvitation(tenantId, email, role),
    onSuccess: (data) => {
      setEmail('');
      setLastCreatedToken(data.token || null);
      setCopied(false);
      void client.invalidateQueries({ queryKey: ['tenant-invitations', tenantId] });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeInvitation(tenantId, id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['tenant-invitations', tenantId] });
    },
  });

  const inviteLink = lastCreatedToken
    ? `${window.location.origin}/onboarding?token=${encodeURIComponent(lastCreatedToken)}`
    : '';

  const handleCopy = () => {
    if (inviteLink) {
      navigator.clipboard.writeText(inviteLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 3000);
    }
  };

  return (
    <div className="invitations-layout">
      {/* Create invitation form */}
      <section className="settings-card">
        <div className="settings-card-heading">
          <UserPlus size={22} />
          <div>
            <h2>Invite a Team Member</h2>
            <p>Send an invitation to join this organization with pre-assigned permissions.</p>
          </div>
        </div>

        <form onSubmit={(e) => { e.preventDefault(); inviteMutation.mutate(); }}>
          <label>
            Recipient Email
            <input
              type="email"
              required
              placeholder="colleague@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </label>
          <label>
            Assigned Role
            <select value={role} onChange={(e) => setRole(e.target.value as any)}>
              <option value="DEVELOPER">DEVELOPER (Spawn previews, build code)</option>
              <option value="QA_REVIEWER">QA_REVIEWER (Review PRs, test checklists)</option>
              <option value="PORTAL_ADMIN">PORTAL_ADMIN (Manage team, projects, policies)</option>
            </select>
          </label>

          {inviteMutation.error && (
            <p role="alert" className="inline-error">{inviteMutation.error.message}</p>
          )}

          <button className="primary-button" disabled={inviteMutation.isPending || !email.trim()} type="submit">
            {inviteMutation.isPending ? 'Generating invite…' : 'Generate Invitation'}
          </button>
        </form>

        {lastCreatedToken && (
          <div className="invite-success-banner">
            <div className="banner-heading">
              <Check size={18} />
              <strong>Invitation created!</strong>
            </div>
            <p>Share this single-use link with the invitee. It will expire in 7 days:</p>
            <div className="token-copy-box">
              <input type="text" readOnly value={inviteLink} />
              <button type="button" className="copy-button" onClick={handleCopy}>
                {copied ? <CheckCheck size={16} /> : <Copy size={16} />}
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Pending & Past Invitations */}
      <section className="settings-card full-width">
        <div className="settings-card-heading">
          <Clock size={22} />
          <div>
            <h2>Pending &amp; Past Invitations</h2>
            <p>Track delivery status and manage unaccepted invitations.</p>
          </div>
        </div>

        {invitations.isPending ? (
          <p className="muted">Loading invitations…</p>
        ) : invitations.isError ? (
          <p className="inline-error">{invitations.error.message}</p>
        ) : invitations.data.length === 0 ? (
          <p className="muted">No invitations sent yet.</p>
        ) : (
          <div className="invitations-table">
            <div className="invitations-table-header">
              <span>Recipient Email</span>
              <span>Role</span>
              <span>Status</span>
              <span>Expires</span>
              <span>Actions</span>
            </div>
            {invitations.data.map((inv) => (
              <div key={inv.id} className="invitation-row">
                <span className="invitation-email">
                  <Mail size={14} />
                  {inv.email}
                </span>
                <span className={`tenant-role-pill ${inv.role.toLowerCase()}`}>{inv.role}</span>
                <span className={`status-pill ${inv.status.toLowerCase()}`}>{inv.status}</span>
                <span className="invitation-expires">{new Date(inv.expiresAt).toLocaleDateString()}</span>
                <div>
                  {inv.status === 'PENDING' && (
                    <button
                      className="danger-button-subtle"
                      disabled={revokeMutation.isPending}
                      onClick={() => revokeMutation.mutate(inv.id)}
                    >
                      Revoke
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function SessionsTab() {
  const client = useQueryClient();
  const sessions = useQuery({
    queryKey: ['user-sessions'],
    queryFn: getSessions,
  });

  const [revokedNotice, setRevokedNotice] = useState<string | null>(null);

  const revokeMutation = useMutation({
    mutationFn: revokeOtherSessions,
    onSuccess: (data) => {
      setRevokedNotice(`Successfully signed out ${data.revokedCount} other active session(s).`);
      void client.invalidateQueries({ queryKey: ['user-sessions'] });
    },
  });

  return (
    <section className="settings-card full-width">
      <div className="settings-card-heading">
        <Laptop size={22} />
        <div>
          <h2>Active Device Sessions</h2>
          <p>These browser sessions are currently authenticated to your KubeSpawn account.</p>
        </div>
      </div>

      {revokedNotice && (
        <div className="inline-success" style={{ marginBottom: '1.25rem' }}>
          <Check size={16} />
          <span>{revokedNotice}</span>
        </div>
      )}

      <div style={{ marginBottom: '1.5rem' }}>
        <button
          className="danger-button"
          disabled={revokeMutation.isPending}
          onClick={() => {
            if (confirm('Are you sure you want to sign out all other browser sessions?')) {
              revokeMutation.mutate();
            }
          }}
        >
          {revokeMutation.isPending ? 'Signing out…' : 'Sign out all other sessions'}
        </button>
      </div>

      {sessions.isPending ? (
        <p className="muted">Loading sessions…</p>
      ) : sessions.isError ? (
        <p className="inline-error">{sessions.error.message}</p>
      ) : sessions.data.length === 0 ? (
        <p className="muted">No active session records found.</p>
      ) : (
        <div className="sessions-list">
          {sessions.data.map((sess) => (
            <article key={sess.sessionId} className={`session-row ${sess.current ? 'current-session' : ''}`}>
              <div className="session-icon">
                <Laptop size={20} />
              </div>
              <div className="session-info">
                <div className="session-title">
                  <strong>Session {sess.sessionId.slice(0, 8)}…</strong>
                  {sess.current && <span className="current-badge">This device</span>}
                </div>
                <small className="session-meta">
                  Created {new Date(sess.creationTime).toLocaleString()} · Last active {new Date(sess.lastAccessTime).toLocaleString()}
                </small>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
