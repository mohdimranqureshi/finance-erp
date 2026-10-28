import { useEffect, useState } from 'react';
import { Plus, ShieldAlert } from 'lucide-react';
import { api } from '../api';
import { Panel, Table } from '../ui';
import { ExportBar } from '../exporters';
import type { Account, Shell } from '../types';

const ACCOUNT_TYPES = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

export default function CompanyView({ companyId, accounts, reload, shell }: {
  companyId: number | ''; accounts: Account[]; reload: () => Promise<void>; shell: Shell;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState('ASSET');
  const [memberEmail, setMemberEmail] = useState('');
  const [memberRole, setMemberRole] = useState('APPROVER');
  const [invitationToken, setInvitationToken] = useState('');
  const [allowSelfApproval, setAllowSelfApproval] = useState(false);

  useEffect(() => {
    if (companyId === '') return;
    api<{ allowSelfApproval: boolean }>(`/api/v1/companies/${companyId}/settings`)
      .then(s => setAllowSelfApproval(s.allowSelfApproval))
      .catch(() => undefined);
  }, [companyId]);

  const setSelfApproval = (allow: boolean) => shell.run(async () => {
    const saved = await api<{ allowSelfApproval: boolean }>(`/api/v1/companies/${companyId}/settings`, {
      method: 'PUT', body: JSON.stringify({ allowSelfApproval: allow }),
    });
    setAllowSelfApproval(saved.allowSelfApproval);
    shell.notify(allow
      ? 'Self-approval is on. The same person can now approve their own invoices and journals, and each one is recorded in the audit log as a self-approval.'
      : 'Self-approval is off. Journals must be approved by someone other than the creator or submitter.');
  });

  const createAccount = async () => {
    const ok = await shell.run(async () => {
      if (!companyId) throw Error('Create or select a company first.');
      await api(`/api/v1/companies/${companyId}/accounts`, {
        method: 'POST', body: JSON.stringify({ code, name, accountType: type }),
      });
      await reload();
      shell.notify('Account created.');
    });
    if (ok) { setCode(''); setName(''); }
  };

  const addMember = async () => {
    const ok = await shell.run(async () => {
      if (!companyId || !memberEmail.trim()) throw Error('Select a company and enter the registered user email.');
      await api(`/api/v1/companies/${companyId}/members`, {
        method: 'POST', body: JSON.stringify({ email: memberEmail.trim(), role: memberRole }),
      });
      shell.notify(`Added ${memberRole.toLowerCase()} member. They must sign in with their own account to approve journals.`);
    });
    if (ok) setMemberEmail('');
  };

  const inviteMember = () => shell.run(async () => {
    if (!companyId || !memberEmail.trim()) throw Error('Select a company and enter an email address.');
    const result = await api<{ invitationToken: string; expiresAt: string; email: string; role: string }>(
      `/api/v1/companies/${companyId}/invitations`,
      { method: 'POST', body: JSON.stringify({ email: memberEmail.trim(), role: memberRole }) });
    setInvitationToken(result.invitationToken);
    shell.notify(`Invitation created for ${result.email}; expires ${new Date(result.expiresAt).toLocaleString()}. Share the token only through a trusted channel.`);
  });

  const inviteLink = `${window.location.origin}/#invite=${invitationToken}`;

  return <>
    <Panel
      title="Approval control"
      description="By default an invoice or journal must be approved by someone other than the person who raised it. If you are the only person who keeps the books, you can allow yourself to approve your own documents."
      aside={<span className={allowSelfApproval ? 'draft' : 'posted'}>
        {allowSelfApproval ? 'Self-approval on' : 'Separation of duties'}
      </span>}>
      <label style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        <input type="checkbox" checked={allowSelfApproval} disabled={shell.busy || !companyId}
          onChange={e => setSelfApproval(e.target.checked)} />
        <span>Let the same user approve the invoices and journals they created</span>
      </label>
      {allowSelfApproval && <p className="hint">
        <ShieldAlert size={14} /> Nobody independently checks these entries. Each self-approval is still stored in the
        audit log as <code>JOURNAL_SELF_APPROVED</code>. An auditor may treat this as a missing internal control, so turn
        it back off once a second person can approve.
      </p>}
      <p className="hint">Only an owner can change this setting, and every change is recorded in the audit log.</p>
    </Panel>

    <Panel
      title="Company team"
      description="Add a separately registered user so journals can be approved by someone other than the person who raised them."
      aside={<span className="count">Maker-checker</span>}>
      <form className="accountform" onSubmit={e => { e.preventDefault(); addMember(); }}>
        <label>Registered user email
          <input type="email" value={memberEmail} onChange={e => setMemberEmail(e.target.value)}
            placeholder="approver@company.in" required />
        </label>
        <label>Role
          <select value={memberRole} onChange={e => setMemberRole(e.target.value)}>
            <option value="APPROVER">Approver</option>
            <option value="ACCOUNTANT">Accountant</option>
            <option value="VIEWER">Viewer</option>
          </select>
        </label>
        <button disabled={shell.busy || !companyId}><Plus size={15} />Add existing user</button>
        <button type="button" className="secondary" disabled={shell.busy || !companyId} onClick={inviteMember}>
          <Plus size={15} />Create invitation
        </button>
      </form>

      {invitationToken && <div className="alert success">
        <strong>One-time invitation token</strong>
        <p>Send this link only through a trusted channel: <code>{inviteLink}</code></p>
        <div><button type="button" className="small secondary"
          onClick={() => navigator.clipboard.writeText(inviteLink)}>Copy invitation link</button></div>
      </div>}

      <p className="hint">Existing users can be added directly. New users can accept a one-time invitation; tokens expire after 48 hours.</p>
    </Panel>

    <div className="grid">
      <Panel
        id="chart-of-accounts"
        title="Chart of accounts"
        description="Configure company-specific accounts"
        aside={<span className="inline">
          <span className="count">{accounts.length} accounts</span>
          <ExportBar printId="chart-of-accounts" filename="chart-of-accounts" rows={accounts}
            columns={[
              { header: 'Code', value: a => a.code },
              { header: 'Account', value: a => a.name },
              { header: 'Type', value: a => a.accountType },
            ]} />
        </span>}>
        <form className="accountform" onSubmit={e => { e.preventDefault(); createAccount(); }}>
          <label>Code<input value={code} onChange={e => setCode(e.target.value)} placeholder="1000" required /></label>
          <label>Account name<input value={name} onChange={e => setName(e.target.value)} placeholder="Bank" required /></label>
          <label>Type
            <select value={type} onChange={e => setType(e.target.value)}>
              {ACCOUNT_TYPES.map(t => <option key={t}>{t}</option>)}
            </select>
          </label>
          <button disabled={shell.busy || !companyId}><Plus size={15} />Add account</button>
        </form>

        <Table columns={3} isEmpty={!accounts.length} empty="Create your first account above."
          head={<tr><th>Code</th><th>Account</th><th>Type</th></tr>}>
          {accounts.map(a => <tr key={a.id}>
            <td className="mono">{a.code}</td><td>{a.name}</td><td><span className="chip">{a.accountType}</span></td>
          </tr>)}
        </Table>
      </Panel>
    </div>
  </>;
}
