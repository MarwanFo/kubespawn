import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Building2, Check, ShieldCheck, UserRound } from 'lucide-react';
import { updateProfile, type Profile } from './api';

export function ProfileView({ profile }: { profile: Profile }) {
  const client = useQueryClient();
  const [displayName, setDisplayName] = useState(profile.displayName);
  const [avatarUrl, setAvatarUrl] = useState(profile.avatarUrl ?? '');
  useEffect(() => { setDisplayName(profile.displayName); setAvatarUrl(profile.avatarUrl ?? ''); }, [profile]);
  const save = useMutation({ mutationFn: () => updateProfile(displayName, avatarUrl), onSuccess: value => {
    client.setQueryData(['me'], value);
  }});

  return <div className="saas-page">
    <div className="page-heading"><div><p className="eyebrow">ACCOUNT</p><h1>Your profile</h1>
      <p>Identity, workspace memberships, and access level.</p></div></div>
    <div className="profile-layout">
      <section className="settings-card">
        <div className="settings-card-heading"><span className="profile-avatar">{profile.avatarUrl
          ? <img src={profile.avatarUrl} alt="" /> : <UserRound size={24} />}</span><div><h2>Personal details</h2>
          <p>These details are displayed to workspace administrators.</p></div></div>
        <form onSubmit={event => { event.preventDefault(); save.mutate(); }}>
          <label>Display name<input required maxLength={120} value={displayName} onChange={event => setDisplayName(event.target.value)} /></label>
          <label>Email<input value={profile.email ?? 'Managed by your identity provider'} disabled /></label>
          <label>Avatar URL<input type="url" maxLength={2048} value={avatarUrl} onChange={event => setAvatarUrl(event.target.value)} placeholder="https://…" /></label>
          {save.error && <p role="alert" className="inline-error">{save.error.message}</p>}
          {save.isSuccess && <p role="status" className="inline-success"><Check size={13} />Profile saved</p>}
          <button className="primary-button" disabled={save.isPending || !displayName.trim()} type="submit">Save profile</button>
        </form>
      </section>
      <section className="settings-card">
        <div className="settings-card-heading"><ShieldCheck size={22} /><div><h2>Access</h2>
          <p>Workspace roles are enforced by the control plane.</p></div></div>
        <div className="identity-meta"><span>Authentication<strong>{profile.authMode === 'local' ? 'Local development' : 'Organization SSO'}</strong></span>
          <span>Platform access<strong>{profile.platformAdmin ? 'Platform administrator' : 'Member'}</strong></span></div>
        <div className="membership-list">{profile.memberships.map(membership => <article key={membership.tenantId}>
          <Building2 size={18} /><div><strong>{membership.tenantName}</strong><small>{membership.tenantKey}</small></div>
          <span>{membership.role.replace('_', ' ')}</span></article>)}</div>
      </section>
    </div>
  </div>;
}
