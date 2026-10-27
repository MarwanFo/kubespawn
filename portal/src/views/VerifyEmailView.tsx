import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate, Link } from 'react-router-dom';
import { Layers3, CheckCircle2, AlertTriangle, ArrowRight, RefreshCw, ShieldCheck, ExternalLink } from 'lucide-react';

export function VerifyEmailView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const email = searchParams.get('email') || 'developer@organization.com';
  const token = searchParams.get('token');

  const [status, setStatus] = useState<'VERIFYING' | 'VERIFIED' | 'EXPIRED'>('VERIFYING');
  const [resending, setResending] = useState(false);

  useEffect(() => {
    // Simulate token verification
    const timer = setTimeout(() => {
      if (token === 'expired') {
        setStatus('EXPIRED');
      } else {
        setStatus('VERIFIED');
      }
    }, 800);
    return () => clearTimeout(timer);
  }, [token]);

  const handleResend = () => {
    setResending(true);
    setTimeout(() => {
      setResending(false);
      setStatus('VERIFYING');
      setTimeout(() => setStatus('VERIFIED'), 1000);
    }, 600);
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">ACCOUNT ACTIVATION</p>
          <h1>Email verification</h1>
          <p>Verifying identity credentials before granting onboarding authorization.</p>
        </div>

        {status === 'VERIFYING' && (
          <div className="success-state-card" style={{ padding: '32px 16px' }}>
            <RefreshCw size={36} className="spin text-indigo" />
            <h2>Validating token…</h2>
            <p>Verifying single-use email token for <strong>{email}</strong>.</p>
          </div>
        )}

        {status === 'VERIFIED' && (
          <div className="success-state-card">
            <CheckCircle2 size={44} className="text-emerald" />
            <h2>Account verified!</h2>
            <p>
              Your email <strong>{email}</strong> has been confirmed.
              Your account is now ready for organization onboarding.
            </p>

            <div className="info-box" style={{ margin: '16px 0', textAlign: 'left' }}>
              <ShieldCheck size={18} />
              <div>
                <strong>Starter Entitlements</strong>
                <p>As a verified new user, you can now create your initial organization or accept an active invitation.</p>
              </div>
            </div>

            <button
              type="button"
              className="primary-button full-width"
              onClick={() => navigate('/onboarding')}
            >
              Continue to Organization Setup <ArrowRight size={15} />
            </button>
          </div>
        )}

        {status === 'EXPIRED' && (
          <div className="success-state-card">
            <AlertTriangle size={44} style={{ color: '#f59e0b' }} />
            <h2>Verification link expired</h2>
            <p>
              Security policy limits verification token lifetime to 15 minutes.
              Request a fresh verification link below.
            </p>

            <div className="mailpit-callout">
              <span className="mailpit-badge">Local Mailbox Catcher</span>
              <p>Check the latest email in Mailpit after requesting a new token.</p>
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
                onClick={handleResend}
                disabled={resending}
              >
                {resending ? (
                  <>Sending new link <RefreshCw size={14} className="spin" /></>
                ) : (
                  'Request New Verification Link'
                )}
              </button>

              <Link to="/login" className="secondary-button full-width">
                Back to Sign in
              </Link>
            </div>
          </div>
        )}

        <div className="auth-card-footer">
          <div className="security-badge">
            <ShieldCheck size={14} /> Single-use tokens · Cryptographically signed callbacks
          </div>
        </div>
      </div>
    </div>
  );
}
