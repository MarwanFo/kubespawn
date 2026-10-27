import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Layers3, Lock, Mail, ArrowRight, ShieldCheck, KeyRound, UserCheck, AlertCircle } from 'lucide-react';

interface QuickUser {
  name: string;
  email: string;
  role: string;
  desc: string;
}

const PRESET_USERS: QuickUser[] = [
  { name: 'Dave Developer', email: 'dev@acme.test', role: 'DEVELOPER', desc: 'PR previews, lifecycle builds, request QA reviews' },
  { name: 'Quinn Reviewer', email: 'qa@acme.test', role: 'QA_REVIEWER', desc: 'QA inbox, checklist validation, approval decisions' },
  { name: 'Alice Admin', email: 'admin@acme.test', role: 'PORTAL_ADMIN', desc: 'Organization tenant admin, members, quotas, policies' },
  { name: 'Ned Newbie', email: 'newbie@test.test', role: 'NEW_USER', desc: 'Verified identity awaiting organization onboarding' },
];

export function LoginView() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    // In local BFF / Keycloak integration:
    // If user enters credentials in local demo mode, direct to dashboard or OIDC
    setTimeout(() => {
      setLoading(false);
      if (email.includes('suspended')) {
        setError('This account has been suspended by an organization administrator.');
      } else {
        navigate('/');
      }
    }, 400);
  };

  const handleSelectPreset = (u: QuickUser) => {
    setEmail(u.email);
    setPassword('AcmeDev123!');
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">KUBESPAWN IDP</p>
          <h1>Welcome back</h1>
          <p>Sign in to your organization workspace and ephemeral preview environments.</p>
        </div>

        {error && (
          <div className="info-box" style={{ borderColor: 'rgba(239, 68, 68, 0.4)', background: 'rgba(239, 68, 68, 0.08)' }}>
            <AlertCircle size={18} style={{ color: '#ef4444' }} />
            <div>
              <strong style={{ color: '#f87171' }}>Authentication failed</strong>
              <p>{error}</p>
            </div>
          </div>
        )}

        <div className="sso-section">
          <a className="primary-button full-width" href="/oauth2/authorization/kubespawn">
            Continue with Keycloak SSO (OIDC) <ArrowRight size={15} />
          </a>
          <div className="auth-divider"><span>or sign in with password</span></div>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <label>
            Work Email
            <div className="input-with-icon">
              <Mail size={16} />
              <input
                type="email"
                required
                autoComplete="username"
                placeholder="developer@organization.com"
                value={email}
                onChange={e => setEmail(e.target.value)}
              />
            </div>
          </label>

          <label>
            <div className="label-with-link">
              Password
              <Link to="/forgot-password">Forgot password?</Link>
            </div>
            <div className="input-with-icon">
              <Lock size={16} />
              <input
                type="password"
                required
                autoComplete="current-password"
                placeholder="••••••••••••"
                value={password}
                onChange={e => setPassword(e.target.value)}
              />
            </div>
          </label>

          <button type="submit" className="primary-button full-width" disabled={loading}>
            {loading ? 'Authenticating…' : 'Sign In'}
          </button>
        </form>

        <div className="preset-user-section">
          <div className="preset-header">
            <UserCheck size={14} />
            <span>Deterministic Test Personas</span>
          </div>
          <div className="preset-grid">
            {PRESET_USERS.map(u => (
              <button
                key={u.email}
                type="button"
                className="preset-chip"
                onClick={() => handleSelectPreset(u)}
                title={`${u.role}: ${u.desc}`}
              >
                <strong>{u.name}</strong>
                <span className="role-tag">{u.role}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="auth-card-footer">
          <div className="footer-links">
            <p>New to KubeSpawn? <Link to="/signup">Create an account</Link></p>
            <span className="footer-dot">·</span>
            <Link to="/mfa-challenge" className="inline-link"><KeyRound size={12} /> MFA challenge</Link>
          </div>
          <div className="security-badge">
            <ShieldCheck size={14} /> Zero browser token storage · HttpOnly session cookies
          </div>
        </div>
      </div>
    </div>
  );
}
