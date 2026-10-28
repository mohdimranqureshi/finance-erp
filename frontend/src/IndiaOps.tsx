import { useEffect, useState } from 'react';
import { api, money } from './api';
import { useToastFeed } from './toast';

type Account = { id: number; code: string; name: string; accountType: string };
type Item = { id: number; number: string; party: string; date: string; dueDate?: string | null; totalAmount: number; outstanding: number; daysOverdue: number; bucket: string };
type OpenItems = { receivables: Item[]; payables: Item[]; totalReceivable: number; totalPayable: number };
type Invoice = { id: number; invoiceNumber?: string; customerName: string; status: string; irn?: string | null };
type Suggestion = { paymentId: number; direction: string; paymentDate: string; amount: number };
type BankLine = { id: number; txnDate: string; description: string; amount: number; matchedPaymentId?: number | null; suggestions?: Suggestion[] };
type Recon = { bookBalance: number; statementBalance: number; difference: number; unmatchedStatementLines: { count: number; total: number }; unmatchedPayments: { count: number; total: number } };
type Period = { id: number; name: string; status: string };
type Schedule = { id: number; invoiceNumber?: string; description: string; totalAmount: number; startDate: string; months: number; recognized: number; remaining: number; deferralJournalStatus: string };

const today = () => new Date().toISOString().slice(0, 10);
const fyStart = () => { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-04-01`; };
const post = <T,>(url: string, body?: unknown) => api<T>(url, { method: 'POST', body: JSON.stringify(body ?? {}) });

export default function IndiaOps({ companyId, accounts, onLedgerChanged }: { companyId: number; accounts: Account[]; onLedgerChanged: () => Promise<void> }) {
  const base = `/api/v1/companies/${companyId}`;
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const [open, setOpen] = useState<OpenItems | null>(null), [invoices, setInvoices] = useState<Invoice[]>([]), [periods, setPeriods] = useState<Period[]>([]);
  const [sel, setSel] = useState<{ kind: 'receipt' | 'payment'; item: Item } | null>(null), [amount, setAmount] = useState(''), [tds, setTds] = useState(''), [payDate, setPayDate] = useState(today()), [payRef, setPayRef] = useState('');
  const [cnInvoice, setCnInvoice] = useState(''), [cnReason, setCnReason] = useState(''), [cnDesc, setCnDesc] = useState(''), [cnHsn, setCnHsn] = useState('998314'), [cnPrice, setCnPrice] = useState(''), [cnRate, setCnRate] = useState('18');
  const [bankId, setBankId] = useState(''), [csv, setCsv] = useState(''), [lines, setLines] = useState<BankLine[]>([]), [recon, setRecon] = useState<Recon | null>(null);
  const [docInvoice, setDocInvoice] = useState(''), [json, setJson] = useState(''), [irn, setIrn] = useState('');
  const [periodId, setPeriodId] = useState(''), [reservesId, setReservesId] = useState('');
  const [schedules, setSchedules] = useState<Schedule[]>([]), [rrInvoice, setRrInvoice] = useState(''), [rrStart, setRrStart] = useState(today()), [rrMonths, setRrMonths] = useState('12');

  const banks = accounts.filter(a => a.accountType === 'ASSET'), equity = accounts.filter(a => a.accountType === 'EQUITY');
  const issued = invoices.filter(i => i.status === 'ISSUED');

  async function load() {
    const [o, i, p, s] = await Promise.all([api<OpenItems>(`${base}/open-items`), api<Invoice[]>(`${base}/sales-invoices`), api<Period[]>(`${base}/periods`), api<Schedule[]>(`${base}/revenue-schedules`)]);
    setOpen(o); setInvoices(i); setPeriods(p); setSchedules(s);
  }
  useEffect(() => { load().catch(e => setError(e.message)); }, [companyId]);
  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : 'Unexpected error'); } finally { setBusy(false); }
  }

  const settle = () => run(async () => {
    if (!sel) return;
    const body = { paymentDate: payDate, amount: Number(amount), tdsAmount: sel.kind === 'receipt' && tds ? Number(tds) : undefined, reference: payRef || undefined };
    await post(`${base}/${sel.kind === 'receipt' ? `sales-invoices/${sel.item.id}/receipts` : `purchase-bills/${sel.item.id}/payments`}`, body);
    setSel(null); setAmount(''); setTds(''); setPayRef(''); await load(); await onLedgerChanged();
    setMessage('Recorded. A draft journal now needs independent approval before it reaches the ledger.');
  });
  const creditNote = () => run(async () => {
    await post(`${base}/sales-invoices/${cnInvoice}/credit-notes`, { noteDate: today(), reason: cnReason, lines: [{ description: cnDesc, hsnSac: cnHsn, quantity: 1, unitPrice: Number(cnPrice), gstRate: Number(cnRate) }] });
    setCnReason(''); setCnDesc(''); setCnPrice(''); await load(); await onLedgerChanged(); setMessage('Credit note created with a draft journal for approval.');
  });
  const refreshBank = async () => {
    if (!bankId) return;
    setLines(await api<BankLine[]>(`${base}/bank/statement-lines?bankAccountId=${bankId}&unmatchedOnly=true`));
    setRecon(await api<Recon>(`${base}/bank/reconciliation?bankAccountId=${bankId}&asOf=${today()}`));
  };
  const importCsv = () => run(async () => {
    const rows = csv.split('\n').map(r => r.trim()).filter(Boolean).map(r => {
      const [date, description, amt, reference] = r.split(',').map(s => s.trim());
      const amountNum = Number(amt);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(amountNum) || amountNum === 0) throw new Error(`Bad row: ${r}`);
      return { date, description, amount: amountNum, reference: reference || undefined };
    });
    const r = await post<{ imported: number }>(`${base}/bank/statement-lines`, { bankAccountId: Number(bankId), lines: rows });
    setCsv(''); await refreshBank(); setMessage(`${r.imported} statement lines imported.`);
  });
  const match = (lineId: number, paymentId: number) => run(async () => { await post(`${base}/bank/statement-lines/${lineId}/match`, { paymentId }); await refreshBank(); setMessage('Matched.'); });
  const gstr1 = () => run(async () => setJson(JSON.stringify(await api(`${base}/gst/gstr1?from=${fyStart()}&to=${today()}`), null, 2)));
  const eInvoice = () => run(async () => setJson(JSON.stringify(await api(`${base}/sales-invoices/${docInvoice}/e-invoice-payload`), null, 2)));
  const recordIrn = () => run(async () => { await post(`${base}/sales-invoices/${docInvoice}/irn`, { irn: irn.trim() }); setIrn(''); await load(); setMessage('IRN recorded.'); });
  const createSchedule = () => run(async () => {
    await post(`${base}/sales-invoices/${rrInvoice}/revenue-schedule`, { startDate: rrStart, months: Number(rrMonths) });
    await load(); await onLedgerChanged(); setMessage('Schedule created. Approve and post the deferral journal before recognition can run.');
  });
  const recognise = () => run(async () => {
    const r = await post<{ draftJournalsCreated: number; skipped: number }>(`${base}/revenue-schedules/recognize`, { asOf: today() });
    await load(); await onLedgerChanged(); setMessage(`${r.draftJournalsCreated} recognition journals drafted, ${r.skipped} skipped (closed period).`);
  });
  const closeYear = () => run(async () => {
    const r = await post<{ journalId: number; netProfit: number }>(`${base}/year-end/closing-journal`, { periodId: Number(periodId), reservesAccountId: Number(reservesId) });
    await onLedgerChanged(); setMessage(`Closing journal #${r.journalId} drafted (net ${money(r.netProfit)}). Approve and post it, then close the period.`);
  });

  const table = (title: string, rows: Item[], kind: 'receipt' | 'payment') => <div className="tablewrap"><table><thead><tr><th>{title}</th><th>Party</th><th>Due</th><th>Ageing</th><th className="right">Outstanding</th><th>Action</th></tr></thead><tbody>
    {rows.map(r => <tr key={r.id}><td className="mono">{r.number}</td><td>{r.party}</td><td>{r.dueDate || r.date}</td><td><span className="chip">{r.bucket}</span></td><td className="right">{money(r.outstanding)}</td>
      <td><button className="small" disabled={busy} onClick={() => { setSel({ kind, item: r }); setAmount(String(r.outstanding)); }}>{kind === 'receipt' ? 'Record receipt' : 'Record payment'}</button></td></tr>)}
    {!rows.length && <tr><td colSpan={6} className="empty">Nothing outstanding.</td></tr>}</tbody></table></div>;

  useToastFeed(error, message);
  return <>
    <div className="eyebrow" style={{ marginTop: 24 }}>INDIA · SETTLEMENT · RECONCILIATION · COMPLIANCE DATA</div>
    <section className="panel below">
      <div className="panelhead"><div><h2>Receivables and payables</h2><p>Receipts support TDS deducted by the customer; payments post as drafts for approval</p></div><span className="count">{open ? `${money(open.totalReceivable)} due / ${money(open.totalPayable)} payable` : ''}</span></div>
      {sel && <form className="accountform" onSubmit={e => { e.preventDefault(); settle(); }}>
        <label>{sel.kind === 'receipt' ? 'Receipt' : 'Payment'} for {sel.item.number}<input type="number" step="any" min="0.01" value={amount} onChange={e => setAmount(e.target.value)} required /></label>
        {sel.kind === 'receipt' && <label>TDS deducted by customer<input type="number" step="any" min="0" value={tds} onChange={e => setTds(e.target.value)} /></label>}
        <label>Date<input type="date" value={payDate} onChange={e => setPayDate(e.target.value)} required /></label>
        <label>Reference<input value={payRef} onChange={e => setPayRef(e.target.value)} /></label>
        <button disabled={busy}>Save</button><button type="button" className="secondary" onClick={() => setSel(null)}>Cancel</button>
      </form>}
      {open && table('Invoice', open.receivables, 'receipt')}{open && table('Vendor bill', open.payables, 'payment')}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Credit note</h2><p>Reverses revenue and GST against an issued invoice</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); creditNote(); }}>
        <label>Invoice<select value={cnInvoice} onChange={e => setCnInvoice(e.target.value)} required><option value="">Select</option>{issued.map(i => <option key={i.id} value={i.id}>{i.invoiceNumber} — {i.customerName}</option>)}</select></label>
        <label>Reason<input value={cnReason} onChange={e => setCnReason(e.target.value)} required /></label>
        <label>Description<input value={cnDesc} onChange={e => setCnDesc(e.target.value)} required /></label>
        <label>HSN/SAC<input value={cnHsn} onChange={e => setCnHsn(e.target.value)} pattern="\d{4}|\d{6}|\d{8}" required /></label>
        <label>Amount before GST<input type="number" step="any" min="0.01" value={cnPrice} onChange={e => setCnPrice(e.target.value)} required /></label>
        <label>GST %<input type="number" step="any" min="0" value={cnRate} onChange={e => setCnRate(e.target.value)} required /></label>
        <button disabled={busy}>Create credit note</button>
      </form>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Bank reconciliation</h2><p>Paste statement rows as <code>YYYY-MM-DD,description,amount,reference</code> (deposits positive, withdrawals negative)</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); importCsv(); }}>
        <label>Bank account<select value={bankId} onChange={e => setBankId(e.target.value)} required><option value="">Select</option>{banks.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <label>Statement rows<textarea value={csv} onChange={e => setCsv(e.target.value)} rows={3} required /></label>
        <button disabled={busy}>Import rows</button><button type="button" className="secondary" disabled={busy || !bankId} onClick={() => run(refreshBank)}>Load unmatched</button>
      </form>
      {recon && <p className="hint">Book {money(recon.bookBalance)} · Statement {money(recon.statementBalance)} · Difference {money(recon.difference)} · {recon.unmatchedStatementLines.count} unmatched statement lines · {recon.unmatchedPayments.count} unmatched ledger payments. Opening balance is not tracked.</p>}
      <div className="tablewrap"><table><thead><tr><th>Date</th><th>Description</th><th className="right">Amount</th><th>Suggested match</th></tr></thead><tbody>
        {lines.map(l => <tr key={l.id}><td>{l.txnDate}</td><td>{l.description}</td><td className="right">{money(l.amount)}</td>
          <td>{l.suggestions?.length ? l.suggestions.map(s => <button key={s.paymentId} className="small" disabled={busy} onClick={() => match(l.id, s.paymentId)}>Match {s.direction.toLowerCase()} #{s.paymentId}</button>) : <span className="muted">No posted payment with this amount within 5 days</span>}</td></tr>)}
        {!lines.length && <tr><td colSpan={4} className="empty">No unmatched lines loaded.</td></tr>}</tbody></table></div>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>GST filing data, e-invoice and e-way bill</h2><p>Payloads are generated for review; nothing is sent to the government portals</p></div></div>
      <form className="accountform" onSubmit={e => e.preventDefault()}>
        <button type="button" className="wide" disabled={busy} onClick={gstr1}>GSTR-1 working data (FY to date)</button>
        <label>Invoice<select value={docInvoice} onChange={e => setDocInvoice(e.target.value)}><option value="">Select</option>{issued.map(i => <option key={i.id} value={i.id}>{i.invoiceNumber} — {i.customerName}{i.irn ? ' (IRN recorded)' : ''}</option>)}</select></label>
        <button type="button" className="secondary" disabled={busy || !docInvoice} onClick={eInvoice}>E-invoice payload</button>
        <label>IRN from the portal<input value={irn} onChange={e => setIrn(e.target.value)} maxLength={64} /></label>
        <button type="button" className="secondary" disabled={busy || !docInvoice || irn.length !== 64} onClick={recordIrn}>Record IRN</button>
      </form>
      {json && <pre className="mono" style={{ maxHeight: 320, overflow: 'auto' }}>{json}</pre>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Revenue recognition</h2><p>Defers an invoice's taxable value, then recognises it monthly (straight-line, full month from the start month)</p></div><button disabled={busy} onClick={recognise}>Recognise due revenue</button></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); createSchedule(); }}>
        <label>Invoice<select value={rrInvoice} onChange={e => setRrInvoice(e.target.value)} required><option value="">Select</option>{issued.map(i => <option key={i.id} value={i.id}>{i.invoiceNumber} — {i.customerName}</option>)}</select></label>
        <label>Start date<input type="date" value={rrStart} onChange={e => setRrStart(e.target.value)} required /></label>
        <label>Months<input type="number" min="1" max="120" value={rrMonths} onChange={e => setRrMonths(e.target.value)} required /></label>
        <button disabled={busy}>Create schedule</button>
      </form>
      <div className="tablewrap"><table><thead><tr><th>Invoice</th><th>Description</th><th>Term</th><th className="right">Total</th><th className="right">Recognised</th><th className="right">Deferred balance</th><th>Deferral journal</th></tr></thead><tbody>
        {schedules.map(s => <tr key={s.id}><td className="mono">{s.invoiceNumber}</td><td>{s.description}</td><td>{s.months} mo from {s.startDate}</td><td className="right">{money(s.totalAmount)}</td><td className="right">{money(s.recognized)}</td><td className="right">{money(s.remaining)}</td><td><span className="chip">{s.deferralJournalStatus}</span></td></tr>)}
        {!schedules.length && <tr><td colSpan={7} className="empty">No schedules yet.</td></tr>}</tbody></table></div>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Year-end close</h2><p>Drafts the entry moving profit or loss to reserves. Approve and post it, then close the period.</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); closeYear(); }}>
        <label>Period<select value={periodId} onChange={e => setPeriodId(e.target.value)} required><option value="">Select</option>{periods.filter(p => p.status === 'OPEN').map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label>Reserves account<select value={reservesId} onChange={e => setReservesId(e.target.value)} required><option value="">Select</option>{equity.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <button disabled={busy}>Draft closing journal</button>
      </form>
    </section>
  </>;
}
