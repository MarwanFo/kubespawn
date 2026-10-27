import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Building2, Ticket, ArrowRight, ShieldCheck, Check, Sparkles, AlertCircle } from 'lucide-react';
import { createOrganization, acceptInvitation, type Profile } from '../api';

export function OnboardingView() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<'create' | 'join'>('create');
  const [orgName, setOrgName] = useState('');
  const [orgKey, setOrgKey] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [created, setCreated] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const createMutation = useMutation({
    mutationFn: () => createOrganization(orgName, orgKey),
    onSuccess: (profile: Profile) => {
      setCreated(true);
      void queryClient.invalidateQueries({ queryKey: ['me'] });
      // If new tenant created, set as active in localStorage
      if (profile.memberships.length > 0) {
        localStorage.setItem('kubespawn_tenant_id', profile.memberships[0].tenantId);
      }
    },
    onError: (err: Error) => {
      setError(err.message || 'Failed to create organization. Please choose a different key.');
    },
  });

  const joinMutation = useMutation({
    mutationFn: () => acceptInvitation(inviteToken),
    onSuccess: () => {
      setCreated(true);
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    },
    onError: (err: Error) => {
      setError(err.message || 'Invalid or expired invitation token.');
    },
  });

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    createMutation.mutate();
  };

  const handleJoin = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    joinMutation.mutate();
  };

  return (
    <div className="onboarding-screen">
      <div className="onboarding-card">
        <div className="onboarding-header">
          <p className="eyebrow">FIRST-TIME SETUP</p>
          <h1>Welcome to KubeSpawn</h1>
          <p>
            Your account is verified. To access ephemeral environments and pull request reviews,
            activate an organization workspace below.
          </p>
        </div>

        {error && (
          <div className="info-box" style={{ borderColor: 'rgba(239, 68, 68, 0.4)', background: 'rgba(239, 68, 68, 0.08)', marginBottom: '16px' }}>
            <AlertCircle size={18} style={{ color: '#ef4444' }} />
            <div>
              <p style={{ color: '#f87171', margin: 0 }}>{error}</p>
            </div>
          </div>
        )}

        <div className="onboarding-tabs">
          <button
            className={`tab-btn ${tab === 'create' ? 'active' : ''}`}
            onClick={() => { setTab('create'); setError(null); }}
          >
            <Building2 size={16} /> Create an Organization
          </button>
          <button
            className={`tab-btn ${tab === 'join' ? 'active' : ''}`}
            onClick={() => { setTab('join'); setError(null); }}
          >
            <Ticket size={16} /> Accept an Invitation
          </button>
        </div>

        {created ? (
          <div className="success-state-card">
            <Sparkles size={44} className="text-emerald" />
            <h2>Workspace Activated!</h2>
            <p>
              Your organization workspace has been established with starter entitlements.
              You have been granted <strong>PORTAL_ADMIN</strong> privileges for this tenant.
            </p>
            <button className="primary-button" onClick={() => navigate('/')}>
              Go to Developer Dashboard &rarr;
            </button>
          </div>
        ) : tab === 'create' ? (
          <form className="onboarding-form" onSubmit={handleCreate}>
            <label>
              Organization Name
              <input
                required
                placeholder="e.g. Acme Corporation"
                value={orgName}
                onChange={e => {
                  setOrgName(e.target.value);
                  if (!orgKey || orgKey === orgName.toLowerCase().replace(/[^a-z0-9]+/g, '-')) {
                    setOrgKey(e.target.value.toLowerCase().replace(/[^a-z0-9]+/g, '-'));
                  }
                }}
              />
            </label>

            <label>
              Organization Key (URL slug)
              <input
                placeholder="e.g. acme-corp"
                pattern="^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$|^[a-z0-9]$"
                value={orgKey}
                onChange={e => setOrgKey(e.target.value.toLowerCase().replace(/[^a-z0-9-]+/g, ''))}
              />
            </label>

            <div className="starter-tier-box">
              <strong>Starter Entitlements Included:</strong>
              <ul>
                <li><Check size={14} /> Up to 5 concurrent pull-request environments</li>
                <li><Check size={14} /> 24-hour default TTL with bounded extension</li>
                <li><Check size={14} /> SHA-256 verified build pipeline & SBOM gate</li>
              </ul>
            </div>

            <button
              type="submit"
              className="primary-button full-width"
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? 'Activating Workspace…' : 'Create Organization & Continue'}
              {!createMutation.isPending && <ArrowRight size={15} />}
            </button>
          </form>
        ) : (
          <form className="onboarding-form" onSubmit={handleJoin}>
            <label>
              Invitation Code / Token
              <input
                required
                placeholder="Paste the 256-bit invitation token from your admin"
                value={inviteToken}
                onChange={e => setInviteToken(e.target.value)}
              />
            </label>
            <p className="help-text">
              Invitations grant role-based access (Developer or QA Reviewer) to specific organization projects.
            </p>

            <button
              type="submit"
              className="primary-button full-width"
              disabled={joinMutation.isPending}
            >
              {joinMutation.isPending ? 'Validating Token…' : 'Validate & Join Organization'}
              {!joinMutation.isPending && <ArrowRight size={15} />}
            </button>
          </form>
        )}

        <div className="onboarding-footer">
          <ShieldCheck size={14} /> Multi-tenant boundary: Sharing an email domain never auto-links an existing organization.
        </div>
      </div>
    </div>
  );
}
