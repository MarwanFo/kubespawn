import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Layers3, KeyRound, ShieldCheck, ArrowRight, ArrowLeft, Smartphone, FileText } from 'lucide-react';

export function MfaChallengeView() {
  const navigate = useNavigate();
  const [code, setCode] = useState(['', '', '', '', '', '']);
  const [useBackup, setUseBackup] = useState(false);
  const [backupCode, setBackupCode] = useState('');
  const [trustBrowser, setTrustBrowser] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const handleDigitChange = (index: number, val: string) => {
    if (val.length > 1) {
      val = val.slice(-1);
    }
    const newCode = [...code];
    newCode[index] = val;
    setCode(newCode);

    // Auto-advance focus to next input
    if (val && index < 5) {
      const nextInput = document.getElementById(`totp-digit-${index + 1}`);
      nextInput?.focus();
    }
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent) => {
    if (e.key === 'Backspace' && !code[index] && index > 0) {
      const prevInput = document.getElementById(`totp-digit-${index - 1}`);
      prevInput?.focus();
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!useBackup && code.some(d => !d)) {
      setError('Please enter the complete 6-digit verification code.');
      return;
    }
    if (useBackup && !backupCode.trim()) {
      setError('Please enter a valid recovery code.');
      return;
    }
    // Successful MFA challenge
    navigate('/');
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card-header">
          <span className="auth-logo"><Layers3 size={28} /></span>
          <p className="eyebrow">TWO-FACTOR AUTHENTICATION</p>
          <h1>Security challenge</h1>
          <p>Protecting access to isolated tenant preview infrastructure.</p>
        </div>

        {error && (
          <div className="info-box" style={{ borderColor: 'rgba(239, 68, 68, 0.4)', background: 'rgba(239, 68, 68, 0.08)' }}>
            <p style={{ color: '#f87171', margin: 0 }}>{error}</p>
          </div>
        )}

        <form className="auth-form" onSubmit={handleSubmit}>
          {!useBackup ? (
            <div>
              <label style={{ textAlign: 'center', marginBottom: '16px' }}>
                Enter the 6-digit code from your authenticator app
              </label>

              <div className="totp-input-group">
                {code.map((digit, idx) => (
                  <input
                    key={idx}
                    id={`totp-digit-${idx}`}
                    type="text"
                    inputMode="numeric"
                    maxLength={1}
                    className="totp-digit-input"
                    value={digit}
                    onChange={e => handleDigitChange(idx, e.target.value)}
                    onKeyDown={e => handleKeyDown(idx, e)}
                    autoFocus={idx === 0}
                  />
                ))}
              </div>
            </div>
          ) : (
            <label>
              Backup Recovery Code
              <div className="input-with-icon">
                <FileText size={16} />
                <input
                  required
                  placeholder="xxxx-xxxx-xxxx"
                  value={backupCode}
                  onChange={e => setBackupCode(e.target.value)}
                  autoFocus
                />
              </div>
            </label>
          )}

          <label className="checkbox-label" style={{ marginTop: '16px' }}>
            <input
              type="checkbox"
              checked={trustBrowser}
              onChange={e => setTrustBrowser(e.target.checked)}
            />
            <span>Trust this browser session for 30 days</span>
          </label>

          <button type="submit" className="primary-button full-width" style={{ marginTop: '16px' }}>
            Verify & Sign In <ArrowRight size={15} />
          </button>

          <button
            type="button"
            className="secondary-button full-width"
            style={{ marginTop: '8px' }}
            onClick={() => {
              setUseBackup(!useBackup);
              setError(null);
            }}
          >
            {useBackup ? (
              <><Smartphone size={14} /> Use Authenticator App</>
            ) : (
              <><KeyRound size={14} /> Use a recovery backup code</>
            )}
          </button>

          <Link to="/login" className="back-link">
            <ArrowLeft size={14} /> Back to sign in
          </Link>
        </form>

        <div className="auth-card-footer">
          <div className="security-badge">
            <ShieldCheck size={14} /> TOTP RFC 6238 · Non-reusable challenge codes
          </div>
        </div>
      </div>
    </div>
  );
}
