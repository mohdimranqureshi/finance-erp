import { useState } from 'react';
import { Plus } from 'lucide-react';
import { api, money } from '../api';
import { Panel, Table } from '../ui';
import { ExportBar } from '../exporters';
import type { Account, Journal, Shell, TrialRow } from '../types';

const todayIso = () => new Date().toISOString().slice(0, 10);

function JournalAction({ journal, busy, onSubmit, onApprove, onPost, onReverse }: {
  journal: Journal; busy: boolean;
  onSubmit: () => void; onApprove: () => void; onPost: () => void; onReverse: () => void;
}) {
  if (journal.status === 'DRAFT')
    return <button className="small" disabled={busy} onClick={onSubmit}>Submit for approval</button>;
  if (journal.status === 'SUBMITTED')
    return <button className="small" disabled={busy} onClick={onApprove}>Approve</button>;
  if (journal.status === 'APPROVED')
    return <button className="small" disabled={busy} onClick={onPost}>Post approved</button>;
  if (journal.status === 'POSTED' && !journal.reversalOfJournalId && !journal.reversedByJournalId)
    return <button className="small secondary" disabled={busy} onClick={onReverse}>Reverse</button>;
  return <span className="muted">
    {journal.reversalOfJournalId ? `Reversal of #${journal.reversalOfJournalId}`
      : journal.reversedByJournalId ? `Reversal draft #${journal.reversedByJournalId} pending`
        : 'In ledger'}
  </span>;
}

export default function JournalsView({ companyId, accounts, journals, trial, debits, credits, reload, shell }: {
  companyId: number | ''; accounts: Account[]; journals: Journal[]; trial: TrialRow[];
  debits: number; credits: number; reload: () => Promise<void>; shell: Shell;
}) {
  const [description, setDescription] = useState('Opening capital');
  const [date, setDate] = useState(todayIso());
  const [debitAccount, setDebitAccount] = useState('');
  const [creditAccount, setCreditAccount] = useState('');
  const [amount, setAmount] = useState('100000');

  const createJournal = () => shell.run(async () => {
    if (!companyId || !debitAccount || !creditAccount) throw Error('Select a company and both accounts.');
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) throw Error('Amount must be greater than zero.');
    const journal = await api<Journal>(`/api/v1/companies/${companyId}/journals`, {
      method: 'POST',
      body: JSON.stringify({
        journalDate: date, description, lines: [
          { accountId: Number(debitAccount), description: 'Debit line', debit: value, credit: 0 },
          { accountId: Number(creditAccount), description: 'Credit line', debit: 0, credit: value },
        ],
      }),
    });
    await reload();
    shell.notify(`Draft journal #${journal.id} created.`);
  });

  const transition = (id: number, action: 'submit' | 'approve' | 'post', done: string) => shell.run(async () => {
    await api(`/api/v1/companies/${companyId}/journals/${id}/${action}`, { method: 'POST' });
    await reload();
    shell.notify(done);
  });

  const reverse = (id: number) => shell.run(async () => {
    if (!window.confirm(`Create an equal-and-opposite reversal for journal #${id}?`)) return;
    const reversal = await api<Journal>(`/api/v1/companies/${companyId}/journals/${id}/reverse`, {
      method: 'POST', body: JSON.stringify({ reversalDate: todayIso() }),
    });
    await reload();
    shell.notify(`Reversal draft #${reversal.id} created for journal #${id}. Submit it for approval before it affects the ledger.`);
  });

  const accountOptions = accounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>);

  return <>
    <div className="grid">
      <Panel title="New journal" description="Balanced double-entry transaction" aside={<span className="draft">Draft</span>}>
        <form className="journalform" onSubmit={e => { e.preventDefault(); createJournal(); }}>
          <label>Description<input value={description} onChange={e => setDescription(e.target.value)} required /></label>
          <label>Date<input type="date" value={date} onChange={e => setDate(e.target.value)} required /></label>
          <label>Debit account
            <select value={debitAccount} onChange={e => setDebitAccount(e.target.value)} required>
              <option value="">Select account</option>{accountOptions}
            </select>
          </label>
          <label>Credit account
            <select value={creditAccount} onChange={e => setCreditAccount(e.target.value)} required>
              <option value="">Select account</option>{accountOptions}
            </select>
          </label>
          <label>Amount (INR)
            <input type="number" min="0.01" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} required />
          </label>
          <button disabled={shell.busy || !companyId}><Plus size={15} />Create draft journal</button>
        </form>
        <p className="hint">Maker-checker workflow is enforced by the API: the submitter cannot approve their own journal.</p>
      </Panel>
    </div>

    <Panel id="journals-list" title="Recent journals" description="Draft → submitted → independently approved → posted"
      aside={<span className="inline">
        <span className="count">{journals.length} total</span>
        <ExportBar printId="journals-list" filename="journals" rows={journals}
          columns={[
            { header: 'Journal', value: j => `JE-${String(j.id).padStart(5, '0')}` },
            { header: 'Date', value: j => j.journalDate },
            { header: 'Description', value: j => j.description },
            { header: 'Status', value: j => j.status },
          ]} />
      </span>}>
      <Table columns={5} isEmpty={!journals.length} empty="No journals yet. Create one above."
        head={<tr><th>Journal</th><th>Date</th><th>Description</th><th>Status</th><th>Action</th></tr>}>
        {journals.map(j => <tr key={j.id}>
          <td className="mono">JE-{String(j.id).padStart(5, '0')}</td>
          <td>{j.journalDate}</td>
          <td>{j.description}</td>
          <td><span className={j.status === 'POSTED' || j.status === 'APPROVED' ? 'posted' : 'draft'}>{j.status}</span></td>
          <td><JournalAction journal={j} busy={shell.busy}
            onSubmit={() => transition(j.id, 'submit', `Journal #${j.id} submitted for independent approval.`)}
            onApprove={() => transition(j.id, 'approve', `Journal #${j.id} approved. It can now be posted.`)}
            onPost={() => transition(j.id, 'post', `Journal #${j.id} posted.`)}
            onReverse={() => reverse(j.id)} /></td>
        </tr>)}
      </Table>
    </Panel>

    <Panel id="journals-trial" title="Trial balance" description="Posted transactions only · current calendar year to date"
      aside={<span className="inline">
        <span className="count">INR</span>
        <ExportBar printId="journals-trial" filename="trial-balance" rows={trial}
          columns={[
            { header: 'Code', value: r => r.code },
            { header: 'Account', value: r => r.name },
            { header: 'Type', value: r => r.accountType },
            { header: 'Debits', value: r => r.debitTotal },
            { header: 'Credits', value: r => r.creditTotal },
          ]} />
      </span>}>
      <Table columns={5} isEmpty={!trial.length} empty="Post a journal to see the trial balance."
        head={<tr><th>Code</th><th>Account</th><th>Type</th><th className="right">Debits</th><th className="right">Credits</th></tr>}
        foot={<tfoot><tr>
          <th colSpan={3}>Total</th>
          <th className="right">{money(debits)}</th>
          <th className="right">{money(credits)}</th>
        </tr></tfoot>}>
        {trial.map(r => <tr key={r.accountId}>
          <td className="mono">{r.code}</td><td>{r.name}</td><td>{r.accountType}</td>
          <td className="right">{money(Number(r.debitTotal))}</td>
          <td className="right">{money(Number(r.creditTotal))}</td>
        </tr>)}
      </Table>
    </Panel>
  </>;
}
