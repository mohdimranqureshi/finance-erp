import { BookOpen } from 'lucide-react';
import { Alert } from '../ui';

export default function SignInView({
  inviteToken, email, setEmail, password, setPassword, displayName, setDisplayName,
  busy, error, message, onSubmit,
}: {
  inviteToken: string;
  email: string; setEmail: (v: string) => void;
  password: string; setPassword: (v: string) => void;
  displayName: string; setDisplayName: (v: string) => void;
  busy: boolean; error: string; message: string; onSubmit: () => void;
}) {
  const inviting = Boolean(inviteToken);
  return <div className="auth-shell">
    <form className="auth-card" onSubmit={e => { e.preventDefault(); onSubmit(); }}>
      <div className="brand">
        <div className="mark"><BookOpen size={19} /></div>
        <div><strong>FinoraAI</strong><small>FINANCE ERP</small></div>
      </div>

      <h1>{inviting ? 'Accept company invitation' : 'Sign in'}</h1>
      <p>{inviting
        ? 'Create your account to join the company that invited you.'
        : 'Use your account to access only companies you belong to. Accounts are created by your administrator.'}</p>

      {error && <Alert kind="error">{error}</Alert>}
      {message && <Alert kind="success">{message}</Alert>}

      {!inviting && <label>Email
        <input type="email" value={email} onChange={e => setEmail(e.target.value)} autoComplete="username" required />
      </label>}

      {inviting && <label>Display name
        <input value={displayName} onChange={e => setDisplayName(e.target.value)} required />
      </label>}

      <label>Password
        <input type="password" value={password} onChange={e => setPassword(e.target.value)}
          autoComplete={inviting ? 'new-password' : 'current-password'} minLength={inviting ? 12 : 1} required />
      </label>
      {inviting && <small>Use at least 12 characters and a unique password.</small>}

      <button disabled={busy}>{busy ? 'Please wait…' : inviting ? 'Accept invitation' : 'Sign in'}</button>
    </form>
  </div>;
}
