import { useCallback, useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { api, money } from '../api';
import { Panel, Table } from '../ui';
import type { Account, Shell } from '../types';

type Party = { id: number; partyType: string; name: string };
type Item = { id: number; party: string; documentNumber: string; documentDate: string; dueDate?: string | null; amount: number; outstanding: number };
type Batch = { id: number; asOfDate: string; status: string; receivableTotal: number; payableTotal: number; equityAdjustment: number; journalStatus: string; journalReversed?: boolean };
type Current = { batch: Batch | null; receivables: Item[]; payables: Item[] };
type GlRow = { accountId: string; debit: string; credit: string };
type PartyRow = { partyId: string; documentNumber: string; documentDate: string; dueDate: string; amount: string };

const emptyParty = (): PartyRow => ({ partyId: '', documentNumber: '', documentDate: '', dueDate: '', amount: '' });
const num = (s: string) => (s ? Number(s) : 0);

export default function OpeningBalancesView({ companyId, accounts, shell }: { companyId: number | ''; accounts: Account[]; shell: Shell }) {
  const base = `/api/v1/companies/${companyId}`;
  const [current, setCurrent] = useState<Current | null>(null);
  const [customers, setCustomers] = useState<Party[]>([]);
  const [vendors, setVendors] = useState<Party[]>([]);
  const [asOf, setAsOf] = useState('');
  const [gl, setGl] = useState<GlRow[]>([{ accountId: '', debit: '', credit: '' }]);
  const [recv, setRecv] = useState<PartyRow[]>([]);
  const [pay, setPay] = useState<PartyRow[]>([]);

  const load = useCallback(async () => {
    if (companyId === '') return;
    const [c, cu, ve] = await Promise.all([
      api<Current>(`${base}/opening-balances`),
      api<Party[]>(`${base}/parties?type=CUSTOMER`), api<Party[]>(`${base}/parties?type=VENDOR`)]);
    setCurrent(c); setCustomers(cu); setVendors(ve);
  }, [base, companyId]);
  useEffect(() => { load().catch(e => shell.notify(e.message)); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  // AR and AP come from the party lists, so those system accounts are not offered for direct entry.
  const glAccounts = accounts.filter(a => !['Trade Receivables', 'Trade Payables'].includes(a.name));
  const sum = (rows: PartyRow[]) => rows.reduce((s, r) => s + num(r.amount), 0);
  const glDebit = gl.reduce((s, r) => s + num(r.debit), 0), glCredit = gl.reduce((s, r) => s + num(r.credit), 0);
  const difference = glDebit + sum(recv) - (glCredit + sum(pay));

  const update = <T,>(rows: T[], set: (r: T[]) => void, i: number, patch: Partial<T>) => set(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)));

  const submit = () => shell.run(async () => {
    if (!asOf) throw Error('Choose the date the balances are as of.');
    const party = (rows: PartyRow[], key: 'customerId' | 'vendorId') => rows.filter(r => r.partyId || r.documentNumber || r.amount).map(r => ({
      [key]: Number(r.partyId), documentNumber: r.documentNumber.trim(), documentDate: r.documentDate, dueDate: r.dueDate || null, amount: Number(r.amount) }));
    await api(`${base}/opening-balances`, { method: 'POST', body: JSON.stringify({
      asOfDate: asOf,
      lines: gl.filter(r => r.accountId).map(r => ({ accountId: Number(r.accountId), debit: num(r.debit), credit: num(r.credit) })),
      receivables: party(recv, 'customerId'), payables: party(pay, 'vendorId') }) });
    await load();
    shell.notify('Opening balances recorded as a draft journal. It needs independent approval before it reaches the ledger.');
  });

  if (companyId === '') return <p className="empty">Select or create a company first.</p>;
  const partyTable = (title: string, rows: PartyRow[], set: (r: PartyRow[]) => void, parties: Party[], label: string) => <>
    <h3 style={{ marginTop: 16 }}>{title}</h3>
    <Table columns={6} isEmpty={!rows.length} empty={`No ${label.toLowerCase()} entered.`}
      head={<tr><th>{label}</th><th>Document no.</th><th>Date</th><th>Due</th><th className="right">Outstanding</th><th /></tr>}>
      {rows.map((r, i) => <tr key={i}>
        <td><select aria-label={label} value={r.partyId} onChange={e => update(rows, set, i, { partyId: e.target.value })} required><option value="">Select</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></td>
        <td><input value={r.documentNumber} onChange={e => update(rows, set, i, { documentNumber: e.target.value })} maxLength={50} required /></td>
        <td><input type="date" value={r.documentDate} max={asOf || undefined} onChange={e => update(rows, set, i, { documentDate: e.target.value })} required /></td>
        <td><input type="date" value={r.dueDate} onChange={e => update(rows, set, i, { dueDate: e.target.value })} /></td>
        <td><input type="number" min="0.01" step="0.01" className="right" value={r.amount} onChange={e => update(rows, set, i, { amount: e.target.value })} required /></td>
        <td><button type="button" className="small secondary" aria-label="Remove row" onClick={() => set(rows.filter((_, k) => k !== i))}><Trash2 size={13} /></button></td>
      </tr>)}
    </Table>
    <button type="button" className="small secondary" onClick={() => set([...rows, emptyParty()])}><Plus size={13} />Add {label.toLowerCase()}</button>
  </>;

  const batch = current?.batch;
  return <>
    {batch && <Panel title="Opening balances on record" aside={<span className={batch.journalStatus === 'POSTED' ? 'posted' : 'draft'}>{batch.journalStatus}</span>}
      description={`As of ${batch.asOfDate}. Receivables ${money(batch.receivableTotal)}, payables ${money(batch.payableTotal)}, balancing to Opening Balance Adjustment ${money(batch.equityAdjustment)}.`}>
      <Table columns={5} isEmpty={!current!.receivables.length && !current!.payables.length} empty="No customer or vendor items."
        head={<tr><th>Party</th><th>Document</th><th>Date</th><th className="right">Amount</th><th className="right">Outstanding</th></tr>}>
        {[...current!.receivables, ...current!.payables].map(i => <tr key={`${i.id}`}><td>{i.party}</td><td className="mono">{i.documentNumber}</td><td>{i.documentDate}</td>
          <td className="right">{money(i.amount)}</td><td className="right">{money(i.outstanding)}</td></tr>)}
      </Table>
      <p className="hint">Receive or pay against these from Overview → Receivables and payables. To replace this batch, post it, reverse it, and post the reversal first.</p>
    </Panel>}

    {!batch && <Panel title="Enter opening balances" description="Start from your trial balance on the day you begin using the app. Enter each ledger balance, and list customer and vendor balances as individual documents so they age and can be settled. Anything left over goes to Opening Balance Adjustment.">
      <form onSubmit={e => { e.preventDefault(); submit(); }}>
        <label>Balances as of<input type="date" value={asOf} onChange={e => setAsOf(e.target.value)} required /></label>
        <h3 style={{ marginTop: 16 }}>Ledger balances</h3>
        <Table columns={4} isEmpty={false} empty="" head={<tr><th>Account</th><th className="right">Debit</th><th className="right">Credit</th><th /></tr>}>
          {gl.map((r, i) => <tr key={i}>
            <td><select aria-label="Account" value={r.accountId} onChange={e => update(gl, setGl, i, { accountId: e.target.value })}><option value="">Select</option>{glAccounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></td>
            <td><input type="number" min="0" step="0.01" className="right" value={r.debit} onChange={e => update(gl, setGl, i, { debit: e.target.value, credit: '' })} /></td>
            <td><input type="number" min="0" step="0.01" className="right" value={r.credit} onChange={e => update(gl, setGl, i, { credit: e.target.value, debit: '' })} /></td>
            <td><button type="button" className="small secondary" aria-label="Remove row" onClick={() => setGl(gl.filter((_, k) => k !== i))}><Trash2 size={13} /></button></td>
          </tr>)}
        </Table>
        <button type="button" className="small secondary" onClick={() => setGl([...gl, { accountId: '', debit: '', credit: '' }])}><Plus size={13} />Add account</button>
        {partyTable('Customer balances (receivables)', recv, setRecv, customers, 'Customer')}
        {partyTable('Vendor balances (payables)', pay, setPay, vendors, 'Vendor')}
        <p className="hint">Debits {money(glDebit + sum(recv))} · Credits {money(glCredit + sum(pay))} · the difference of {money(Math.abs(difference))} will be posted to Opening Balance Adjustment as a {difference > 0 ? 'credit' : 'debit'}.</p>
        <button disabled={shell.busy}>Create draft opening journal</button>
      </form>
    </Panel>}
  </>;
}
