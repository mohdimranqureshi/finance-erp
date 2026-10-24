import { useState } from 'react';
import { Plus } from 'lucide-react';
import { api } from '../api';
import { Panel } from '../ui';
import type { Shell } from '../types';

export default function UsersView({ shell }: { shell: Shell }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const createUser = async () => {
    const ok = await shell.run(async () => {
      if (password.length < 12) throw Error('Use a password with at least 12 characters.');
      await api('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), password, displayName: name.trim() }),
      });
      shell.notify(`Account created for ${email.trim()}. Share the password through a trusted channel, then add them to a company.`);
    });
    if (ok) { setName(''); setEmail(''); setPassword(''); }
  };

  return <Panel
    title="Add user"
    description="Only administrators can create accounts. Share the first password through a trusted channel; the account has no company access until you add it to a company team."
    aside={<span className="count">Admin</span>}>
    <form className="accountform" onSubmit={e => { e.preventDefault(); createUser(); }}>
      <label>Display name
        <input value={name} onChange={e => setName(e.target.value)} maxLength={180} required />
      </label>
      <label>Email
        <input type="email" value={email} onChange={e => setEmail(e.target.value)} autoComplete="off" maxLength={254} required />
      </label>
      <label>Password
        <input type="password" value={password} onChange={e => setPassword(e.target.value)}
          autoComplete="new-password" minLength={12} maxLength={72} required />
      </label>
      <button disabled={shell.busy}><Plus size={15} />{shell.busy ? 'Adding user…' : 'Add user'}</button>
    </form>
  </Panel>;
}
