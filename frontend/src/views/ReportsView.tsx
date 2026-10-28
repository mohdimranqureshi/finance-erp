import { useState } from 'react';
import { BarChart3, BookOpen } from 'lucide-react';
import { api, money } from '../api';
import { Panel, Table } from '../ui';
import { ExportBar } from '../exporters';
import type { Account, Shell } from '../types';

type Row = { accountId: number; code: string; name: string; amount: number };
type Pnl = { from: string; to: string; income: Row[]; expenses: Row[]; totalIncome: number; totalExpenses: number; netProfit: number };
type TrialRow = { accountId: number; code: string; name: string; accountType: string; debitTotal: number; creditTotal: number; lastEntryDate?: string | null };
type LedgerLine = { journalId: number; date: string; description: string; lineDescription?: string | null; debit: number; credit: number; balance: number };
type Ledger = {
  code: string; name: string; accountType: string; normalBalance: 'DEBIT' | 'CREDIT';
  from: string; to: string; openingBalance: number; lines: LedgerLine[];
  totalDebits: number; totalCredits: number; closingBalance: number;
};

const iso = (d: Date) => d.toISOString().slice(0, 10);
const financialYear = (offset = 0) => {
  const now = new Date();
  const year = (now.getMonth() >= 3 ? now.getFullYear() : now.getFullYear() - 1) + offset;
  return { from: `${year}-04-01`, to: `${year + 1}-03-31`, label: `FY ${year}-${String(year + 1).slice(2)}` };
};

/** Shows a balance with its Dr/Cr side so a negative number never has to be read as a sign. */
const sided = (value: number, normal: 'DEBIT' | 'CREDIT') => {
  const amount = Number(value) || 0;
  const side = amount < 0 ? (normal === 'CREDIT' ? 'Dr' : 'Cr') : normal === 'CREDIT' ? 'Cr' : 'Dr';
  return `${money(Math.abs(amount))} ${side}`;
};

export default function ReportsView({ companyId, accounts, shell }: {
  companyId: number | ''; accounts: Account[]; shell: Shell;
}) {
  const thisFy = financialYear();
  const [from, setFrom] = useState(thisFy.from);
  const [to, setTo] = useState(iso(new Date()));
  const [accountId, setAccountId] = useState('');
  const [pnl, setPnl] = useState<Pnl | null>(null);
  const [trial, setTrial] = useState<TrialRow[] | null>(null);
  const [ledger, setLedger] = useState<Ledger | null>(null);

  const range = `from=${from}&to=${to}`;
  const base = `/api/v1/companies/${companyId}/reports`;

  const requirePeriod = () => {
    if (!companyId) throw Error('Select a company first.');
    if (from > to) throw Error("'From' must be on or before 'To'.");
  };

  const runStatements = () => shell.run(async () => {
    requirePeriod();
    const [p, t] = await Promise.all([
      api<Pnl>(`${base}/profit-and-loss?${range}`),
      api<TrialRow[]>(`${base}/trial-balance?${range}`),
    ]);
    setPnl(p); setTrial(t);
    shell.notify(`Statements ready for ${from} to ${to}. Posted journals only.`);
  });

  const runLedger = () => shell.run(async () => {
    requirePeriod();
    if (!accountId) throw Error('Select an account to see its ledger.');
    setLedger(await api<Ledger>(`${base}/ledger?accountId=${accountId}&${range}`));
  });

  const preset = (next: { from: string; to: string }) => { setFrom(next.from); setTo(next.to); };
  const now = new Date();
  const presets = [
    { label: 'This month', from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(now) },
    { label: 'This quarter', from: iso(new Date(now.getFullYear(), Math.floor(now.getMonth() / 3) * 3, 1)), to: iso(now) },
    { label: thisFy.label, from: thisFy.from, to: thisFy.to },
    { label: financialYear(-1).label, from: financialYear(-1).from, to: financialYear(-1).to },
  ];

  const debitTotal = (trial || []).reduce((s, r) => s + Number(r.debitTotal || 0), 0);
  const creditTotal = (trial || []).reduce((s, r) => s + Number(r.creditTotal || 0), 0);

  return <>
    <Panel
      title="Reporting period"
      description="Every report below covers posted journals only, between and including these dates."
      aside={<span className="count">Posted only</span>}>
      <form className="accountform" onSubmit={e => { e.preventDefault(); runStatements(); }}>
        <label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} required /></label>
        <label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} required /></label>
        <button disabled={shell.busy || !companyId}><BarChart3 size={15} />Run profit &amp; loss</button>
      </form>
      <div className="inline">
        {presets.map(p => <button key={p.label} type="button" className="small secondary"
          onClick={() => preset(p)}>{p.label}</button>)}
      </div>
    </Panel>

    {pnl && <Panel
      id="report-pnl"
      title="Profit and loss"
      description={`${pnl.from} to ${pnl.to}`}
      aside={<span className="inline">
        <span className={Number(pnl.netProfit) >= 0 ? 'posted' : 'draft'}>
          {Number(pnl.netProfit) >= 0 ? 'Profit' : 'Loss'} {money(Math.abs(Number(pnl.netProfit)))}
        </span>
        <ExportBar printId="report-pnl" filename={`profit-and-loss-${pnl.from}-to-${pnl.to}`}
          rows={[...pnl.income.map(r => ({ ...r, group: 'Income' })),
          ...pnl.expenses.map(r => ({ ...r, group: 'Expense' }))]}
          columns={[
            { header: 'Group', value: r => r.group },
            { header: 'Code', value: r => r.code },
            { header: 'Account', value: r => r.name },
            { header: 'Amount', value: r => r.amount },
          ]} />
      </span>}>
      <div className="print-only"><strong>Profit and loss</strong> · {pnl.from} to {pnl.to} · posted journals only</div>
      <Table columns={3} isEmpty={!pnl.income.length && !pnl.expenses.length}
        empty="No posted income or expense entries in this period."
        head={<tr><th>Code</th><th>Account</th><th className="right">Amount</th></tr>}
        foot={<tfoot>
          <tr><th colSpan={2}>Total income</th><th className="right">{money(pnl.totalIncome)}</th></tr>
          <tr><th colSpan={2}>Total expenses</th><th className="right">({money(pnl.totalExpenses)})</th></tr>
          <tr><th colSpan={2}>{Number(pnl.netProfit) >= 0 ? 'Net profit' : 'Net loss'}</th>
            <th className="right">{money(pnl.netProfit)}</th></tr>
        </tfoot>}>
        {[...pnl.income.map(r => <tr key={`i${r.accountId}`}>
          <td className="mono">{r.code}</td><td>{r.name}</td>
          <td className="right">{money(r.amount)}</td></tr>),
        ...pnl.expenses.map(r => <tr key={`e${r.accountId}`}>
          <td className="mono">{r.code}</td><td>{r.name}</td>
          <td className="right">({money(r.amount)})</td></tr>)]}
      </Table>
    </Panel>}

    {trial && <Panel id="report-trial" title="Trial balance" description={`${from} to ${to}`}
      aside={<span className="inline">
        <span className={debitTotal === creditTotal ? 'posted' : 'draft'}>
          {debitTotal === creditTotal ? 'Balanced' : 'Out of balance'}</span>
        <ExportBar printId="report-trial" filename={`trial-balance-${from}-to-${to}`} rows={trial}
          columns={[
            { header: 'Code', value: r => r.code },
            { header: 'Account', value: r => r.name },
            { header: 'Type', value: r => r.accountType },
            { header: 'Last entry', value: r => r.lastEntryDate || '' },
            { header: 'Debits', value: r => r.debitTotal },
            { header: 'Credits', value: r => r.creditTotal },
          ]} />
      </span>}>
      <div className="print-only"><strong>Trial balance</strong> · {from} to {to} · posted journals only</div>
      <Table columns={6} isEmpty={!trial.length} empty="Nothing posted in this period."
        head={<tr><th>Code</th><th>Account</th><th>Type</th><th>Last entry</th><th className="right">Debits</th><th className="right">Credits</th></tr>}
        foot={<tfoot><tr><th colSpan={4}>Total</th>
          <th className="right">{money(debitTotal)}</th><th className="right">{money(creditTotal)}</th></tr></tfoot>}>
        {trial.map(r => <tr key={r.accountId}>
          <td className="mono">{r.code}</td><td>{r.name}</td><td>{r.accountType}</td>
          <td>{r.lastEntryDate || <span className="muted">—</span>}</td>
          <td className="right">{money(Number(r.debitTotal))}</td>
          <td className="right">{money(Number(r.creditTotal))}</td>
        </tr>)}
      </Table>
    </Panel>}

    <Panel
      id="report-ledger"
      title={<><BookOpen size={16} /> Account ledger</>}
      description="Opening balance, every posted entry in the period, and the running balance for one account."
      aside={ledger ? <ExportBar printId="report-ledger"
        filename={`ledger-${ledger.code}-${ledger.from}-to-${ledger.to}`} rows={ledger.lines}
        columns={[
          { header: 'Date', value: l => l.date },
          { header: 'Journal', value: l => `JE-${String(l.journalId).padStart(5, '0')}` },
          { header: 'Narration', value: l => l.lineDescription || l.description },
          { header: 'Debit', value: l => l.debit },
          { header: 'Credit', value: l => l.credit },
          { header: 'Balance', value: l => l.balance },
        ]} /> : undefined}>
      {ledger && <div className="print-only">
        <strong>Ledger · {ledger.code} {ledger.name}</strong> · {ledger.from} to {ledger.to} · posted journals only
      </div>}
      <form className="accountform" onSubmit={e => { e.preventDefault(); runLedger(); }}>
        <label>Account
          <select value={accountId} onChange={e => setAccountId(e.target.value)} required>
            <option value="">Select account</option>
            {accounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}
          </select>
        </label>
        <button disabled={shell.busy || !companyId}><BookOpen size={15} />Show ledger</button>
      </form>

      {ledger && <Table columns={6} isEmpty={false} empty=""
        head={<tr><th>Date</th><th>Journal</th><th>Narration</th>
          <th className="right">Debit</th><th className="right">Credit</th><th className="right">Balance</th></tr>}
        foot={<tfoot><tr>
          <th colSpan={3}>Closing balance</th>
          <th className="right">{money(ledger.totalDebits)}</th>
          <th className="right">{money(ledger.totalCredits)}</th>
          <th className="right">{sided(ledger.closingBalance, ledger.normalBalance)}</th>
        </tr></tfoot>}>
        {[<tr key="opening">
          <td>{ledger.from}</td><td className="muted">—</td>
          <td><strong>Opening balance</strong></td>
          <td /><td />
          <td className="right">{sided(ledger.openingBalance, ledger.normalBalance)}</td>
        </tr>,
        ...ledger.lines.map((l, i) => <tr key={`${l.journalId}-${i}`}>
          <td>{l.date}</td>
          <td className="mono">JE-{String(l.journalId).padStart(5, '0')}</td>
          <td>{l.lineDescription || l.description}</td>
          <td className="right">{Number(l.debit) ? money(l.debit) : ''}</td>
          <td className="right">{Number(l.credit) ? money(l.credit) : ''}</td>
          <td className="right">{sided(l.balance, ledger.normalBalance)}</td>
        </tr>)]}
      </Table>}
      {!ledger && <p className="hint">Pick an account and select the period above.</p>}
    </Panel>
  </>;
}
