import { useCallback, useEffect, useState } from 'react';
import { api, money } from '../api';
import { Panel, Stat, Table } from '../ui';
import type { Account, Shell } from '../types';

type Heads = { taxable?: number; igst: number; cgst: number; sgst: number; total: number };
type Gstr3b = {
  period: string; itcBasis: string;
  table3_1: { a_outwardTaxable: Heads; c_nilRatedExempt: { taxable: number }; d_inwardReverseCharge: Heads };
  table3_2_interStateToUnregistered: { placeOfSupply: string; taxable: number; igst: number }[];
  table4: { a3_inwardReverseCharge: Heads; a5_allOtherItc: Heads; a5_supplierCreditNotesNetted: Heads; c_netItcAvailable: Heads; d2_ineligibleItc: Heads };
  gstr2bAvailableItc?: Heads;
  table6_1: { liability: Heads; cashForForwardCharge: Heads; cashForReverseCharge: Heads; totalCash: Heads; itcCarriedForward: Heads };
  warnings: string[]; note: string;
};
type Deduction = {
  billId: number; billNumber: string; deductionDate: string; vendorName: string; section: string; rate: number;
  tdsDeducted: number; unallocated: number; depositDueDate: string; panMissing: boolean;
};
type Challan = { id: number; bsrCode: string; challanSerial: string; depositDate: string; taxAmount: number; allocated: number; journalStatus: string };
type Return26q = {
  returnDueDate: string; deductees: { billId: number; billNumber: string; vendorName: string; pan?: string | null; section: string; tdsDeducted: number; indicativeLateDepositInterest: number }[];
  totals: { tdsDeducted: number; indicativeLateDepositInterest: number }; problems: string[]; note: string;
};

const pad = (n: number) => String(n).padStart(2, '0');
const prevMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return `${pad(d.getMonth() + 1)}${d.getFullYear()}`; };
const fyOf = (d: Date) => { const s = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${s}-${pad((s + 1) % 100)}`; };
const quarterOf = (d: Date) => Math.floor(((d.getMonth() + 9) % 12) / 3) + 1;
const today = () => new Date().toISOString().slice(0, 10);
const yearAgo = () => { const d = new Date(); d.setFullYear(d.getFullYear() - 1); return d.toISOString().slice(0, 10); };

const HeadRow = ({ label, h }: { label: string; h?: Heads }) => h ? <tr>
  <td>{label}</td><td className="right">{h.taxable !== undefined ? money(h.taxable) : ''}</td>
  <td className="right">{money(h.igst)}</td><td className="right">{money(h.cgst)}</td><td className="right">{money(h.sgst)}</td><td className="right">{money(h.total)}</td></tr> : null;

export default function ReturnsView({ companyId, accounts, shell }: { companyId: number | ''; accounts: Account[]; shell: Shell }) {
  const base = `/api/v1/companies/${companyId}`;
  const [period, setPeriod] = useState(prevMonth());
  const [basis, setBasis] = useState('books');
  const [g, setG] = useState<Gstr3b | null>(null);

  const [deductions, setDeductions] = useState<Deduction[]>([]);
  const [challans, setChallans] = useState<Challan[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [bsr, setBsr] = useState(''), [serial, setSerial] = useState(''), [depDate, setDepDate] = useState(today());
  const [bank, setBank] = useState(''), [interest, setInterest] = useState(''), [fee, setFee] = useState(''), [expense, setExpense] = useState('');
  const [fy, setFy] = useState(fyOf(new Date())), [quarter, setQuarter] = useState(quarterOf(new Date()));
  const [q, setQ] = useState<Return26q | null>(null);

  const loadTds = useCallback(async () => {
    if (companyId === '') return;
    const [d, c] = await Promise.all([
      api<Deduction[]>(`${base}/tds/deductions?from=${yearAgo()}&to=${today()}&unallocatedOnly=true`),
      api<Challan[]>(`${base}/tds/challans`)]);
    setDeductions(d); setChallans(c);
    setPicked(prev => new Set([...prev].filter(id => d.some(x => x.billId === id))));
  }, [base, companyId]);
  useEffect(() => { setG(null); setQ(null); loadTds().catch(e => shell.notify(e.message)); }, [loadTds]); // eslint-disable-line react-hooks/exhaustive-deps

  const runGstr3b = () => shell.run(async () => {
    if (!/^(0[1-9]|1[0-2])\d{4}$/.test(period)) throw Error('Enter the period as MMYYYY, for example 042026.');
    setG(await api<Gstr3b>(`${base}/gst/gstr3b?period=${period}&itcBasis=${basis}`));
  });

  const selected = deductions.filter(d => picked.has(d.billId));
  const selectedTotal = selected.reduce((s, d) => s + Number(d.unallocated), 0);
  const toggle = (id: number) => setPicked(prev => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  const recordChallan = () => shell.run(async () => {
    if (!selected.length) throw Error('Tick the deductions this challan paid.');
    if (!bank) throw Error('Choose the bank account the tax was paid from.');
    await api(`${base}/tds/challans`, { method: 'POST', body: JSON.stringify({
      bsrCode: bsr.trim(), challanSerial: serial.trim(), depositDate: depDate, taxAmount: selectedTotal,
      interestAmount: interest ? Number(interest) : 0, feeAmount: fee ? Number(fee) : 0,
      bankAccountId: Number(bank), expenseAccountId: expense ? Number(expense) : null,
      allocations: selected.map(d => ({ billId: d.billId, amount: d.unallocated })) }) });
    setPicked(new Set()); setBsr(''); setSerial(''); setInterest(''); setFee('');
    await loadTds();
    shell.notify('Challan recorded with a draft journal. It needs independent approval before it reaches the ledger.');
  });

  const run26q = () => shell.run(async () => setQ(await api<Return26q>(`${base}/tds/26q?fy=${fy}&quarter=${quarter}`)));

  if (companyId === '') return <p className="empty">Select or create a company first.</p>;
  const banks = accounts.filter(a => a.accountType === 'ASSET'), expenses = accounts.filter(a => a.accountType === 'EXPENSE');
  const cols = <tr><th>Item</th><th className="right">Taxable</th><th className="right">IGST</th><th className="right">CGST</th><th className="right">SGST</th><th className="right">Total</th></tr>;

  return <>
    <Panel title="GSTR-3B working sheet" description="Computed from posted journals for the month, with ITC set off in the order the law requires. Use it to review before you file on the GST portal; this app does not file returns."
      aside={<span className="count">Review aid</span>}>
      <form className="accountform" onSubmit={e => { e.preventDefault(); runGstr3b(); }}>
        <label>Period (MMYYYY)<input value={period} onChange={e => setPeriod(e.target.value)} maxLength={6} inputMode="numeric" required /></label>
        <label>ITC taken from<select value={basis} onChange={e => setBasis(e.target.value)}>
          <option value="books">Your books</option><option value="gstr2b">GSTR-2B (imported)</option></select></label>
        <button disabled={shell.busy}>Compute</button>
      </form>
      {g && <>
        {g.warnings.map((w, i) => <div key={i} className="alert" role="status">{w}</div>)}
        <section className="stats">
          <Stat label="Cash to pay" value={money(g.table6_1.totalCash.total)} note="Forward charge plus reverse charge" />
          <Stat label="Of which reverse charge" value={money(g.table6_1.cashForReverseCharge.total)} note="Always paid in cash" />
          <Stat label="Net ITC available" value={money(g.table4.c_netItcAvailable.total)} note={g.itcBasis === 'gstr2b' ? 'Per GSTR-2B' : 'Per your books'} />
          <Stat label="ITC carried forward" value={money(g.table6_1.itcCarriedForward.total)} note="Unused credit" />
        </section>
        <Table columns={6} isEmpty={false} empty="" head={cols}>
          <tr><th colSpan={6}>3.1 Outward and reverse-charge supplies</th></tr>
          <HeadRow label="Taxable outward supplies (net of credit notes)" h={g.table3_1.a_outwardTaxable} />
          <tr><td>Nil-rated, exempt</td><td className="right">{money(g.table3_1.c_nilRatedExempt.taxable)}</td><td colSpan={4} /></tr>
          <HeadRow label="Inward supplies liable to reverse charge" h={g.table3_1.d_inwardReverseCharge} />
          <tr><th colSpan={6}>4 Eligible ITC</th></tr>
          <HeadRow label="Reverse-charge ITC" h={g.table4.a3_inwardReverseCharge} />
          <HeadRow label="All other ITC" h={g.table4.a5_allOtherItc} />
          <HeadRow label="Supplier credit notes netted" h={g.table4.a5_supplierCreditNotesNetted} />
          <HeadRow label="Ineligible ITC (costed)" h={g.table4.d2_ineligibleItc} />
          <tr><th colSpan={6}>6.1 Payment of tax</th></tr>
          <HeadRow label="Tax liability (forward charge)" h={g.table6_1.liability} />
          <HeadRow label="Cash for forward charge" h={g.table6_1.cashForForwardCharge} />
          <HeadRow label="Cash for reverse charge" h={g.table6_1.cashForReverseCharge} />
          <HeadRow label="ITC carried forward" h={g.table6_1.itcCarriedForward} />
        </Table>
        {g.table3_2_interStateToUnregistered.length > 0 && <p className="hint">Inter-state supplies to unregistered buyers: {g.table3_2_interStateToUnregistered.map(r => `state ${r.placeOfSupply} ${money(r.taxable)}`).join(', ')}</p>}
        <p className="hint">{g.note}</p>
      </>}
    </Panel>

    <Panel title="TDS deposits" description="Tax you deducted from vendors and have not yet linked to a deposit. Tick what one challan paid, enter the details from the bank receipt, and a draft journal is created."
      aside={<span className="count">{deductions.length} awaiting deposit</span>}>
      <Table columns={7} isEmpty={!deductions.length} empty="All TDS deductions in the last year are linked to a challan."
        head={<tr><th /><th>Bill</th><th>Vendor</th><th>Section</th><th className="right">TDS to deposit</th><th>Deduction</th><th>Due by</th></tr>}>
        {deductions.map(d => <tr key={d.billId}>
          <td><input type="checkbox" aria-label={`Select bill ${d.billNumber}`} checked={picked.has(d.billId)} onChange={() => toggle(d.billId)} /></td>
          <td className="mono">{d.billNumber}</td><td>{d.vendorName}{d.panMissing && <> <span className="draft">PAN missing</span></>}</td>
          <td>{d.section} @ {d.rate}%</td><td className="right">{money(d.unallocated)}</td><td>{d.deductionDate}</td>
          <td className={new Date(d.depositDueDate) < new Date() ? 'draft' : ''}>{d.depositDueDate}</td></tr>)}
      </Table>
      {deductions.length > 0 && <form className="accountform" onSubmit={e => { e.preventDefault(); recordChallan(); }}>
        <label>BSR code (7 digits)<input value={bsr} onChange={e => setBsr(e.target.value)} pattern="\d{7}" maxLength={7} required /></label>
        <label>Challan serial<input value={serial} onChange={e => setSerial(e.target.value)} pattern="\d{1,5}" maxLength={5} required /></label>
        <label>Deposit date<input type="date" value={depDate} onChange={e => setDepDate(e.target.value)} max={today()} required /></label>
        <label>Paid from<select value={bank} onChange={e => setBank(e.target.value)} required><option value="">Select</option>{banks.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <label>Interest (if any)<input type="number" min="0" step="0.01" value={interest} onChange={e => setInterest(e.target.value)} /></label>
        <label>Late fee (if any)<input type="number" min="0" step="0.01" value={fee} onChange={e => setFee(e.target.value)} /></label>
        {(Number(interest) > 0 || Number(fee) > 0) && <label>Expense account<select value={expense} onChange={e => setExpense(e.target.value)} required><option value="">Select</option>{expenses.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>}
        <button disabled={shell.busy || !picked.size}>Record challan for {money(selectedTotal)}</button>
      </form>}
      {challans.length > 0 && <Table columns={5} isEmpty={false} empty="" head={<tr><th>Challan</th><th>Date</th><th className="right">Tax</th><th className="right">Linked</th><th>Journal</th></tr>}>
        {challans.slice(0, 10).map(c => <tr key={c.id}><td className="mono">{c.bsrCode}/{c.challanSerial}</td><td>{c.depositDate}</td>
          <td className="right">{money(c.taxAmount)}</td><td className="right">{money(c.allocated)}</td><td><span className={c.journalStatus === 'POSTED' ? 'posted' : 'draft'}>{c.journalStatus}</span></td></tr>)}
      </Table>}
    </Panel>

    <Panel title="Form 26Q working data" description="Quarterly TDS return data with problems to fix first. Prepare and validate the actual file in the NSDL return preparation utility.">
      <form className="accountform" onSubmit={e => { e.preventDefault(); run26q(); }}>
        <label>Financial year<input value={fy} onChange={e => setFy(e.target.value)} pattern="\d{4}-\d{2}" placeholder="2026-27" required /></label>
        <label>Quarter<select value={quarter} onChange={e => setQuarter(Number(e.target.value))}>{[1, 2, 3, 4].map(n => <option key={n} value={n}>Q{n}</option>)}</select></label>
        <button disabled={shell.busy}>Show</button>
      </form>
      {q && <>
        {q.problems.length > 0 && <div className="alert error" role="alert"><strong>Fix before filing</strong><ul>{q.problems.map((p, i) => <li key={i}>{p}</li>)}</ul></div>}
        <p className="hint">Due {q.returnDueDate} · TDS deducted {money(q.totals.tdsDeducted)} · indicative late-deposit interest {money(q.totals.indicativeLateDepositInterest)}</p>
        <Table columns={5} isEmpty={!q.deductees.length} empty="No TDS deducted in this quarter."
          head={<tr><th>Vendor</th><th>PAN</th><th>Section</th><th className="right">TDS</th><th className="right">Late interest</th></tr>}>
          {q.deductees.map(d => <tr key={d.billId}><td>{d.vendorName}<br /><small className="mono muted">{d.billNumber}</small></td><td className="mono">{d.pan || '—'}</td>
            <td>{d.section}</td><td className="right">{money(d.tdsDeducted)}</td><td className="right">{money(d.indicativeLateDepositInterest)}</td></tr>)}
        </Table>
        <p className="hint">{q.note}</p>
      </>}
    </Panel>
  </>;
}
