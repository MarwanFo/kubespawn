import { useState, useMemo } from 'react';
import { useSearchParams, useNavigate, Link } from 'react-router-dom';
import { Layers3, Lock, ShieldCheck, CheckCircle2, ArrowRight } from 'lucide-react';

interface StrengthCriterion {
  label: string;
  met: boolean;
}

export function ResetPasswordView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const email = searchParams.get('email') || 'developer@organization.com';

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const criteria: StrengthCriterion[] = useMemo(() => {
    return [
      { label: 'At least 12 characters', met: password.length >= 12 },
      { label: 'Uppercase & lowercase letters', met: /[A-Z]/.test(password) && /[a-z]/.test(password) },
      { label: 'At least one number', met: /[0-9]/.test(password) },
      { label: 'At least one symbol (!@#$%^&*)', met: /[^A-Za-z0-9]/.test(password) },
    ];
  }, [password]);

  const strengthScore = criteria.filter(c => c.met).length;
  const passwordsMatch = password.length > 0 && password === confirmPassword;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!passwordsMatch) {
      setError('Passwords do not match.');
      return;
    }
    if (strengthScore < 3) {
      setError('Password does not meet required strength criteria.');
      return;
    }
    setSuccess(true);
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">CREDENTIAL RECOVERY</p>
          <h1>Set new password</h1>
          <p>Choose a secure, strong password for <strong>{email}</strong>.</p>
        </div>

        {success ? (
          <div className="success-state-card">
            <CheckCircle2 size={44} className="text-emerald" />
            <h2>Password updated</h2>
            <p>
              Your credentials have been securely updated. Existing browser sessions on other devices have been invalidated.
            </p>
            <button
              type="button"
              className="primary-button full-width"
              onClick={() => navigate('/login')}
            >
              Sign In with New Password <ArrowRight size={15} />
            </button>
          </div>
        ) : (
          <form className="auth-form" onSubmit={handleSubmit}>
            {error && (
              <div className="info-box" style={{ borderColor: 'rgba(239, 68, 68, 0.4)', background: 'rgba(239, 68, 68, 0.08)' }}>
                <p style={{ color: '#f87171', margin: 0 }}>{error}</p>
              </div>
            )}

            <label>
              New Password
              <div className="input-with-icon">
                <Lock size={16} />
                <input
                  type="password"
                  required
                  autoComplete="new-password"
                  placeholder="••••••••••••"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                />
              </div>
            </label>

            {password.length > 0 && (
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

            <label>
              Confirm Password
              <div className="input-with-icon">
                <Lock size={16} />
                <input
                  type="password"
                  required
                  autoComplete="new-password"
                  placeholder="••••••••••••"
                  value={confirmPassword}
                  onChange={e => setConfirmPassword(e.target.value)}
                />
              </div>
            </label>

            {confirmPassword.length > 0 && !passwordsMatch && (
              <p style={{ color: '#f87171', fontSize: '12px', marginTop: '-4px' }}>Passwords do not match</p>
            )}

            <button
              type="submit"
              className="primary-button full-width"
              disabled={!passwordsMatch || strengthScore < 3}
            >
              Update Password
            </button>

            <Link to="/login" className="back-link">
              Back to sign in
            </Link>
          </form>
        )}

        <div className="auth-card-footer">
          <div className="security-badge">
            <ShieldCheck size={14} /> Session invalidation on reset · Bcrypt/Argon2 hashing
          </div>
        </div>
      </div>
    </div>
  );
}
