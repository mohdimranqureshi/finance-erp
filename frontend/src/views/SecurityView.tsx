import { useState } from 'react';
import { KeyRound, ShieldCheck, ShieldOff } from 'lucide-react';
import { api } from '../api';
import { Alert, Panel } from '../ui';
import type { Shell } from '../types';

type Setup = { secret: string; otpauthUri: string };

/** Self-service TOTP: setup shows the secret as text (no QR library), confirm with a code, show recovery codes once. */
export default function SecurityView({ shell, mfaEnabled, onChanged }: {
  shell: Shell; mfaEnabled: boolean; onChanged: (enabled: boolean) => void;
}) {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);

  const begin = () => shell.run(async () => {
    setRecoveryCodes([]);
    setSetup(await api<Setup>('/api/v1/auth/mfa/setup', { method: 'POST' }));
    setCode('');
  });

  const enable = () => shell.run(async () => {
    if (!/^\d{6}$/.test(code)) throw Error('Enter the 6-digit code shown in your authenticator app.');
    const result = await api<{ recoveryCodes: string[] }>('/api/v1/auth/mfa/enable', {
      method: 'POST', body: JSON.stringify({ code }),
    });
    setRecoveryCodes(result.recoveryCodes);
    setSetup(null); setCode('');
    onChanged(true);
    shell.notify('Two-factor authentication is on. Save your recovery codes now. Other sessions were signed out.');
  });

  const disable = () => shell.run(async () => {
    if (!password || !code.trim()) throw Error('Enter your password and a current code or recovery code.');
    await api('/api/v1/auth/mfa/disable', { method: 'POST', body: JSON.stringify({ password, code: code.trim() }) });
    setPassword(''); setCode(''); setRecoveryCodes([]);
    onChanged(false);
    shell.notify('Two-factor authentication is off. Other sessions were signed out.');
  });

  const groups = (secret: string) => secret.match(/.{1,4}/g)?.join(' ') ?? secret;

  return <>
    {recoveryCodes.length > 0 && <Panel
      title="Recovery codes"
      description="Each code signs you in once if you lose your authenticator. Store them somewhere safe and offline. They will not be shown again."
      aside={<span className="count">{recoveryCodes.length} codes</span>}>
      <pre className="mono">{recoveryCodes.join('\n')}</pre>
      <div className="inline">
        <button className="secondary" onClick={() => setRecoveryCodes([])}>I have saved these codes</button>
      </div>
    </Panel>}

    <Panel
      title="Two-factor authentication"
      description="Protect your account with a time-based one-time code (TOTP) from an authenticator app such as Microsoft Authenticator, Google Authenticator or 1Password."
      aside={<span className={mfaEnabled ? 'posted' : 'draft'}>{mfaEnabled ? 'On' : 'Off'}</span>}>

      {!mfaEnabled && !setup && <div className="inline">
        <button disabled={shell.busy} onClick={begin}><ShieldCheck size={15} />Set up two-factor authentication</button>
      </div>}

      {!mfaEnabled && setup && <>
        <Alert>
          <strong>1. Add this account to your authenticator app</strong>
          <p>Choose “enter a setup key” and type the key below (time-based, 6 digits, 30 seconds), or paste the
            otpauth link into an app that accepts it. Keep this key private.</p>
        </Alert>
        <p className="hint">Setup key</p>
        <pre className="mono">{groups(setup.secret)}</pre>
        <p className="hint">otpauth link</p>
        <pre className="mono">{setup.otpauthUri}</pre>
        <form className="accountform" onSubmit={e => { e.preventDefault(); enable(); }}>
          <label>2. Enter the current code
            <input value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} inputMode="numeric"
              autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="123456" required />
          </label>
          <button disabled={shell.busy}><KeyRound size={15} />Turn on</button>
          <button type="button" className="secondary" disabled={shell.busy}
            onClick={() => { setSetup(null); setCode(''); }}>Cancel</button>
        </form>
        <p className="hint">The setup expires after 15 minutes. Two-factor authentication is not active until a code is confirmed.</p>
      </>}

      {mfaEnabled && <>
        <p className="hint">Sign-in asks for a code from your authenticator app. HTTP Basic API access is refused for this account.
          To turn it off, confirm your password and a current code or an unused recovery code.</p>
        <form className="accountform" onSubmit={e => { e.preventDefault(); disable(); }}>
          <label>Password
            <input type="password" value={password} onChange={e => setPassword(e.target.value)}
              autoComplete="current-password" maxLength={72} required />
          </label>
          <label>Code or recovery code
            <input value={code} onChange={e => setCode(e.target.value)} autoComplete="one-time-code"
              spellCheck={false} maxLength={32} required />
          </label>
          <button className="danger" disabled={shell.busy}><ShieldOff size={15} />Turn off</button>
        </form>
      </>}
    </Panel>
  </>;
}
