import { useState, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Layers3, Lock, Mail, User, Building2, ShieldCheck, CheckCircle2, ArrowRight, RefreshCw, ExternalLink } from 'lucide-react';

interface StrengthCriterion {
  label: string;
  met: boolean;
}

export function SignupView() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    name: '',
    email: '',
    organization: '',
    password: '',
    agree: false,
  });
  const [success, setSuccess] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  const criteria: StrengthCriterion[] = useMemo(() => {
    const p = form.password;
    return [
      { label: 'At least 12 characters', met: p.length >= 12 },
      { label: 'Uppercase & lowercase letters', met: /[A-Z]/.test(p) && /[a-z]/.test(p) },
      { label: 'At least one number', met: /[0-9]/.test(p) },
      { label: 'At least one symbol (!@#$%^&*)', met: /[^A-Za-z0-9]/.test(p) },
    ];
  }, [form.password]);

  const strengthScore = criteria.filter(c => c.met).length;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (strengthScore < 3) return;
    setSuccess(true);
    setResendCooldown(30);
  };

  const handleResend = () => {
    if (resendCooldown > 0 || resending) return;
    setResending(true);
    setTimeout(() => {
      setResending(false);
      setResendCooldown(30);
    }, 600);
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">START TESTING PULL REQUESTS</p>
          <h1>Create your account</h1>
          <p>Deploy disposable, verified preview environments directly from GitHub.</p>
        </div>

        {success ? (
          <div className="success-state-card">
            <CheckCircle2 size={44} className="text-emerald" />
            <h2>Verification email dispatched</h2>
            <p>
              We have sent a single-use verification link to <strong>{form.email}</strong>.
              Follow the instructions in the email to activate your account and proceed to organization onboarding.
            </p>

            <div className="mailpit-callout">
              <span className="mailpit-badge">Local Mailbox Catcher</span>
              <p>In local development, all outbound emails are captured by Mailpit without external SMTP delivery.</p>
              <a
                href="http://localhost:8025"
                target="_blank"
                rel="noreferrer"
                className="secondary-button"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}
              >
                Open Local Mailbox (Mailpit) <ExternalLink size={14} />
              </a>
            </div>

            <div className="verification-actions">
              <button
                type="button"
                className="primary-button full-width"
                onClick={() => navigate(`/verify-email?email=${encodeURIComponent(form.email)}`)}
              >
                Proceed to Verify Email <ArrowRight size={15} />
              </button>

              <button
                type="button"
                className="secondary-button full-width"
                onClick={handleResend}
                disabled={resendCooldown > 0 || resending}
              >
                {resending ? (
                  <>Dispatched <RefreshCw size={14} className="spin" /></>
                ) : resendCooldown > 0 ? (
                  `Resend email in ${resendCooldown}s`
                ) : (
                  'Resend verification email'
                )}
              </button>
            </div>
          </div>
        ) : (
          <form className="auth-form" onSubmit={handleSubmit}>
            <div className="sso-section" style={{ marginBottom: '16px' }}>
              <a className="secondary-button full-width" href="/oauth2/authorization/kubespawn">
                Sign up with Organization SSO (Keycloak) <ArrowRight size={15} />
              </a>
              <div className="auth-divider"><span>or register with work email</span></div>
            </div>

            <label>
              Full Name
              <div className="input-with-icon">
                <User size={16} />
                <input
                  required
                  autoComplete="name"
                  placeholder="Jane Developer"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                />
              </div>
            </label>

            <label>
              Work Email
              <div className="input-with-icon">
                <Mail size={16} />
                <input
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="jane@company.com"
                  value={form.email}
                  onChange={e => setForm({ ...form, email: e.target.value })}
                />
              </div>
            </label>

            <label>
              Initial Organization Name
              <div className="input-with-icon">
                <Building2 size={16} />
                <input
                  required
                  placeholder="Acme Engineering"
                  value={form.organization}
                  onChange={e => setForm({ ...form, organization: e.target.value })}
                />
              </div>
            </label>

            <label>
              Password
              <div className="input-with-icon">
                <Lock size={16} />
                <input
                  type="password"
                  required
                  autoComplete="new-password"
                  placeholder="••••••••••••"
                  value={form.password}
                  onChange={e => setForm({ ...form, password: e.target.value })}
                />
              </div>
            </label>

            {/* Live Password Strength Meter */}
            {form.password.length > 0 && (
              <div className="password-strength-container">
                <div className="strength-bar-track">
                  <div
                    className={`strength-bar-fill score-${strengthScore}`}
                    style={{ width: `${(strengthScore / 4) * 100}%` }}
                  />
                </div>
                <ul className="strength-criteria-list">
                  {criteria.map((c, i) => (
                    <li key={i} className={c.met ? 'criterion-met' : 'criterion-unmet'}>
                      <span className="criterion-indicator">{c.met ? '✓' : '○'}</span>
                      {c.label}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <label className="checkbox-label">
              <input
                type="checkbox"
                required
                checked={form.agree}
                onChange={e => setForm({ ...form, agree: e.target.checked })}
              />
              <span>I agree to KubeSpawn security terms and isolated tenant boundary policies.</span>
            </label>

            <button
              type="submit"
              className="primary-button full-width"
              disabled={strengthScore < 3}
            >
              Create Account & Organization
            </button>
          </form>
        )}

        <div className="auth-card-footer">
          <p>Already have an account? <Link to="/login">Sign in</Link></p>
          <div className="security-badge">
            <ShieldCheck size={14} /> Zero browser token storage · Non-escalating registration
          </div>
        </div>
      </div>
    </div>
  );
}
