import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Layers3, Mail, ArrowLeft, CheckCircle2, ShieldCheck, ExternalLink, ArrowRight } from 'lucide-react';

export function ForgotPasswordView() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setSent(true);
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">ACCOUNT RECOVERY</p>
          <h1>Reset password</h1>
          <p>Enter your verified work email address and we will send you secure recovery instructions.</p>
        </div>

        {sent ? (
          <div className="success-state-card">
            <CheckCircle2 size={44} className="text-emerald" />
            <h2>Check your mailbox</h2>
            <p>
              If an active account exists for <strong>{email}</strong>, a password reset link has been dispatched.
              Per our security contract, recovery responses never disclose registered email existence.
            </p>

            <div className="mailpit-callout">
              <span className="mailpit-badge">Local Mailbox Catcher</span>
              <p>In local development, inspect the recovery email caught in Mailpit without external SMTP delivery.</p>
              <a
                href="http://localhost:8025"
                target="_blank"
                rel="noreferrer"
                className="secondary-button"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}
              >
                Inspect Mailpit Mailbox <ExternalLink size={14} />
              </a>
            </div>

            <div className="verification-actions">
              <button
                type="button"
                className="primary-button full-width"
                onClick={() => navigate(`/reset-password?email=${encodeURIComponent(email)}`)}
              >
                Open Password Reset Form <ArrowRight size={15} />
              </button>

              <Link to="/login" className="secondary-button full-width">
                <ArrowLeft size={15} /> Return to sign in
              </Link>
            </div>
          </div>
        ) : (
          <form className="auth-form" onSubmit={handleSubmit}>
            <label>
              Work Email
              <div className="input-with-icon">
                <Mail size={16} />
                <input
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="developer@organization.com"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                />
              </div>
            </label>

            <button type="submit" className="primary-button full-width">
              Send Reset Instructions
            </button>

            <Link to="/login" className="back-link">
              <ArrowLeft size={14} /> Back to sign in
            </Link>
          </form>
        )}

        <div className="auth-card-footer">
          <div className="security-badge">
            <ShieldCheck size={14} /> Non-disclosing recovery · Rate-limited email tokens
          </div>
        </div>
      </div>
    </div>
  );
}
