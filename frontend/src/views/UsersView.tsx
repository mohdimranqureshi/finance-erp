import { useCallback, useEffect, useState } from 'react';
import { Plus, Trash2, UserCheck, UserX } from 'lucide-react';
import { api } from '../api';
import { Panel, Table } from '../ui';
import type { Shell } from '../types';

type User = {
  id: number; email: string; displayName: string;
  active: boolean; admin: boolean; createdAt: string; companyCount: number;
};

export default function UsersView({ shell, currentEmail }: { shell: Shell; currentEmail: string }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [users, setUsers] = useState<User[]>([]);

  const loadUsers = useCallback(async () => setUsers(await api<User[]>('/api/v1/admin/users')), []);
  useEffect(() => { loadUsers().catch(() => undefined); }, [loadUsers]);

  const createUser = async () => {
    const ok = await shell.run(async () => {
      if (password.length < 12) throw Error('Use a password with at least 12 characters.');
      await api('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), password, displayName: name.trim() }),
      });
      await loadUsers();
      shell.notify(`Account created for ${email.trim()}. Share the password through a trusted channel, then add them to a company.`);
    });
    if (ok) { setName(''); setEmail(''); setPassword(''); }
  };

  const setActive = (user: User, active: boolean) => shell.run(async () => {
    await api(`/api/v1/admin/users/${user.id}/status`, { method: 'PUT', body: JSON.stringify({ active }) });
    await loadUsers();
    shell.notify(active
      ? `${user.email} can sign in again.`
      : `${user.email} is deactivated and can no longer sign in. Their accounting history is unchanged.`);
  });

  const remove = (user: User) => shell.run(async () => {
    if (!window.confirm(`Permanently delete ${user.email}? This cannot be undone. Deactivate instead if you only want to block access.`)) return;
    await api(`/api/v1/admin/users/${user.id}`, { method: 'DELETE' });
    await loadUsers();
    shell.notify(`Deleted ${user.email}. Audit events recorded against that address are retained.`);
  });

  return <>
    <Panel
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
    </Panel>

    <Panel
      title="Accounts"
      description="Deactivating blocks sign-in but keeps every journal, invoice and approval intact. Deleting is only possible for an account that has never touched the ledger."
      aside={<span className="count">{users.length} accounts</span>}>
      <Table columns={6} isEmpty={!users.length} empty="No accounts yet."
        head={<tr><th>User</th><th>Email</th><th>Role</th><th className="right">Companies</th><th>Status</th><th>Action</th></tr>}>
        {users.map(user => {
          const isSelf = user.email.toLowerCase() === currentEmail.toLowerCase();
          return <tr key={user.id}>
            <td>{user.displayName}</td>
            <td className="mono">{user.email}</td>
            <td><span className="chip">{user.admin ? 'Administrator' : 'User'}</span></td>
            <td className="right">{user.companyCount}</td>
            <td><span className={user.active ? 'posted' : 'draft'}>{user.active ? 'Active' : 'Deactivated'}</span></td>
            <td>{isSelf ? <span className="muted">This is you</span> : <span className="inline">
              {user.active
                ? <button className="small secondary" disabled={shell.busy} onClick={() => setActive(user, false)}>
                  <UserX size={13} />Deactivate</button>
                : <button className="small secondary" disabled={shell.busy} onClick={() => setActive(user, true)}>
                  <UserCheck size={13} />Activate</button>}
              <button className="small danger" disabled={shell.busy} onClick={() => remove(user)}>
                <Trash2 size={13} />Delete</button>
            </span>}</td>
          </tr>;
        })}
      </Table>
    </Panel>
  </>;
}
