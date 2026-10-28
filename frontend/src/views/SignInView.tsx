import { useState } from 'react';
import { BookOpen, Building2, Eye, EyeOff, Lock, ShieldCheck, Sparkles } from 'lucide-react';
import { useToastFeed } from '../toast';

const POINTS = [
  { icon: ShieldCheck, text: 'Maker-checker approval on every journal, with an append-only audit trail.' },
  { icon: Building2, text: 'India-first GST and TDS sub-ledgers, invoices, bills and statutory reports.' },
  { icon: Sparkles, text: 'AI proposes, people approve — nothing reaches the ledger unreviewed.' },
];

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
  const [showPassword, setShowPassword] = useState(false);
  useToastFeed(error, message);

  return <div className="auth-shell">
    <div className="auth-split">
      <aside className="auth-aside">
        <div className="brand">
          <div className="mark"><BookOpen size={19} /></div>
          <div><strong>FinoraAI</strong><small>FINANCE ERP</small></div>
        </div>
        <h2>Accounting you can inspect, verify and trust.</h2>
        <ul className="auth-points">
          {POINTS.map(({ icon: Icon, text }) => <li key={text}><Icon size={16} />{text}</li>)}
        </ul>
        <p className="auth-note">Use synthetic data until your accounting and security review is complete.</p>
      </aside>

      <form className="auth-card" onSubmit={e => { e.preventDefault(); onSubmit(); }}>
        <span className="auth-badge"><Lock size={12} />{inviting ? 'Invitation' : 'Secure sign in'}</span>

        <h1>{inviting ? 'Accept company invitation' : 'Welcome back'}</h1>
        <p>{inviting
          ? 'Create your account to join the company that invited you.'
          : 'You can only reach companies you belong to. Accounts are created by your administrator.'}</p>

        {!inviting && <label>Email          <input type="email" value={email} onChange={e => setEmail(e.target.value)}
            placeholder="you@company.in" autoComplete="username" required />
        </label>}

        {inviting && <label>Display name
          <input value={displayName} onChange={e => setDisplayName(e.target.value)}
            placeholder="Your full name" required />
        </label>}

        <label>Password
          <span className="pw-field">
            <input type={showPassword ? 'text' : 'password'} value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete={inviting ? 'new-password' : 'current-password'}
              minLength={inviting ? 12 : 1} required />
            <button type="button" className="pw-toggle" aria-pressed={showPassword}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              onClick={() => setShowPassword(!showPassword)}>
              {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </span>
        </label>
        {inviting && <small>Use at least 12 characters and a unique password.</small>}

        <button className="block" disabled={busy}>
          {busy ? 'Please wait…' : inviting ? 'Accept invitation' : 'Sign in'}
        </button>

        {!inviting && <small>No account yet? Your administrator creates it and adds you to a company.</small>}
      </form>
    </div>
  </div>;
}
