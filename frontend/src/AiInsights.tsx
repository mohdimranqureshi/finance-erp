import { useEffect, useState } from 'react';
import { Landmark, ListChecks, Search, TrendingUp, Upload } from 'lucide-react';
import { api, apiUpload, money } from './api';
import { useToastFeed } from './toast';

const post = <T,>(url: string, body?: unknown) => api<T>(url, { method: 'POST', body: JSON.stringify(body ?? {}) });
const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => today().slice(0, 8) + '01';

export type Figure = { label: string; value: string; source: { kind: 'ACCOUNT' | 'SALES_INVOICE' | 'PURCHASE_BILL'; ref: string; label: string; id: number } | null };
type LedgerLine = { journalId: number; date: string; description: string; lineDescription?: string | null; debit: number; credit: number };
type Ledger = { lines: LedgerLine[]; openingBalance: number; closingBalance: number; totalDebits: number; totalCredits: number };
type Doc = Record<string, unknown>;

const je = (id: number | string) => `JE-${String(id).padStart(5, '0')}`;
const label = (k: string) => k.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase());
const cell = (k: string, v: unknown) => {
  if (v == null || v === '') return '—';
  if (k === 'journalId' || k === 'otherJournalId') return je(String(v));
  if (/percent/i.test(k)) return `${v}%`;
  if (/amount|total|average|typical|thisMonth|monthly|outstanding|difference/i.test(k) && !/Id$/.test(k) && !isNaN(Number(v))) return money(Number(v));
  return String(v);
};

function Rows({ rows }: { rows: Record<string, unknown>[] }) {
  if (!rows.length) return null;
  const keys = Object.keys(rows[0]);
  return <div className="tablewrap"><table><thead><tr>{keys.map(k => <th key={k}>{label(k)}</th>)}</tr></thead>
    <tbody>{rows.map((r, i) => <tr key={i}>{keys.map(k => <td key={k}>{cell(k, r[k])}</td>)}</tr>)}</tbody></table></div>;
}

/** Cited figures from an /ask answer; clicking one shows the ledger lines or document behind it. */
export function FigureList({ figures, basis, base }: { figures: Figure[]; basis?: { from: string; to: string }; base: string }) {
  const [open, setOpen] = useState<string | null>(null), [ledger, setLedger] = useState<Ledger | null>(null), [doc, setDoc] = useState<Doc | null>(null), [err, setErr] = useState('');
  async function toggle(f: Figure) {
    const s = f.source;
    if (!s) return;
    if (open === s.ref) { setOpen(null); return; }
    setOpen(s.ref); setLedger(null); setDoc(null); setErr('');
    try {
      if (s.kind === 'ACCOUNT') setLedger(await api<Ledger>(`${base}/reports/ledger?accountId=${s.id}&from=${basis?.from ?? '1900-01-01'}&to=${basis?.to ?? today()}`));
      else setDoc(await api<Doc>(`${base}/${s.kind === 'SALES_INVOICE' ? 'sales-invoices' : 'purchase-bills'}/${s.id}`));
    } catch (e) { setErr(e instanceof Error ? e.message : 'Could not load the source.'); }
  }
  const shown = figures.filter(f => f.label || f.value);
  if (!shown.length) return null;
  const docKeys = ['invoiceNumber', 'vendorInvoiceNumber', 'invoiceDate', 'billDate', 'totalAmount', 'status', 'journalEntryId'];
  return <div className="meta">
    <p>Figures used{shown.some(f => f.source) ? ' (select one to see where it comes from)' : ''}:</p>
    <div className="inline" style={{ flexWrap: 'wrap', gap: 6 }}>
      {shown.map((f, i) => f.source
        ? <button type="button" key={i} className={open === f.source.ref ? 'small' : 'small secondary'} onClick={() => toggle(f)}>{f.label}{f.value ? `: ${f.value}` : ''}</button>
        : <span key={i} className="chip">{f.label}{f.value ? `: ${f.value}` : ''}</span>)}
    </div>
    {err && <p className="hint">{err}</p>}
    {ledger && <div className="tablewrap"><table><thead><tr><th>Date</th><th>Journal</th><th>Narration</th><th className="right">Debit</th><th className="right">Credit</th></tr></thead><tbody>
      {ledger.lines.slice(-10).map((l, i) => <tr key={i}><td>{l.date}</td><td className="mono">{je(l.journalId)}</td><td>{l.lineDescription || l.description}</td>
        <td className="right">{Number(l.debit) ? money(l.debit) : ''}</td><td className="right">{Number(l.credit) ? money(l.credit) : ''}</td></tr>)}
      {!ledger.lines.length && <tr><td colSpan={5} className="empty">No posted entries in this period.</td></tr>}</tbody></table>
      <p className="hint">Latest {Math.min(10, ledger.lines.length)} of {ledger.lines.length} posted lines. Period totals: Dr {money(ledger.totalDebits)} / Cr {money(ledger.totalCredits)}.</p></div>}
    {doc && <p className="hint">{docKeys.filter(k => doc[k] != null).map(k => `${label(k)}: ${k === 'totalAmount' ? money(Number(doc[k])) : String(doc[k])}`).join(' · ')}</p>}
  </div>;
}

type Metrics = {
  series: { month: string; revenue: number; expenses: number; netProfit: number }[]; cashBalance: number; cashBasis: string; monthlyBurn: number; runwayMonths: number | null;
  totalReceivable: number; totalPayable: number; daysSalesOutstanding: number | null;
  receivableAgeing: { bucket: string; amount: number }[]; payableAgeing: { bucket: string; amount: number }[];
  topCustomersByOutstanding: { name: string; outstanding: number }[]; topVendorsLast90Days: { name: string; amount: number }[];
};
type Checklist = { period: { id: number; name: string; status: string; start: string; end: string }; items: { key: string; title: string; status: 'DONE' | 'TODO' | 'INFO'; detail: string }[]; done: number; total: number; readyToClose: boolean; narrative?: string };
type Anomaly = { severity: string; code: string; title: string; detail: string; count: number; items: Record<string, unknown>[] };
type FluxRow = { accountId: number; code: string; name: string; type: string; current: number; previous: number; change: number; changePercent: number | null; favourable: boolean; commentary?: string | null; drivers: { journalId: number; description: string; amount: number }[] };
type Flux = { period: { from: string; to: string }; comparePeriod: { from: string; to: string }; totals: Record<'income' | 'expenses' | 'netProfit', { current: number; previous: number; change: number }>; rows: FluxRow[]; summary?: string };
type Suggestion = { lineId: number; txnDate: string; description: string; amount: number; recommendedPaymentId?: number; confidence: string; reason: string; candidates: { paymentId: number; paymentDate: string; reference?: string | null; dayDiff: number }[]; nearMisses: { paymentId: number; paymentDate: string; reference?: string | null; amount: number; difference: number }[] };
type Reconcile = { suggestions: Suggestion[]; unmatchedWithoutCandidate: number; note?: string | null };
type Period = { id: number; name: string; status: string };
type Account = { id: number; code: string; name: string; accountType: string };

function Bars({ series }: { series: Metrics['series'] }) {
  const max = Math.max(1, ...series.flatMap(s => [Number(s.revenue), Number(s.expenses)]));
  const w = 600, h = 150, bw = w / Math.max(1, series.length);
  return <svg viewBox={`0 0 ${w} ${h + 20}`} style={{ width: '100%', maxHeight: 220 }} role="img" aria-label="Revenue and expenses by month">
    {series.map((s, i) => {
      const rh = (Number(s.revenue) / max) * h, eh = (Number(s.expenses) / max) * h;
      return <g key={s.month}>
        <rect x={i * bw + 4} y={h - rh} width={bw / 2 - 5} height={rh} fill="var(--primary)"><title>{`${s.month} revenue ${money(s.revenue)}`}</title></rect>
        <rect x={i * bw + bw / 2} y={h - eh} width={bw / 2 - 5} height={eh} fill="var(--muted)" opacity=".55"><title>{`${s.month} expenses ${money(s.expenses)}`}</title></rect>
        <text x={i * bw + bw / 2} y={h + 14} fontSize="9" textAnchor="middle" fill="var(--muted)">{s.month.slice(5)}</text>
      </g>;
    })}
  </svg>;
}

const Stat = ({ name, value, hint }: { name: string; value: string; hint?: string }) =>
  <div style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 12 }}><div className="muted" style={{ fontSize: 12 }}>{name}</div><strong style={{ fontSize: 18 }}>{value}</strong>{hint && <div className="muted" style={{ fontSize: 11 }}>{hint}</div>}</div>;

export default function AiInsights({ companyId, enabled, onProposals }: { companyId: number; enabled: boolean; onProposals: () => Promise<void> }) {
  const base = `/api/v1/companies/${companyId}`;
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const [metrics, setMetrics] = useState<Metrics | null>(null), [periods, setPeriods] = useState<Period[]>([]), [accounts, setAccounts] = useState<Account[]>([]);
  const [periodId, setPeriodId] = useState(''), [checklist, setChecklist] = useState<Checklist | null>(null);
  const [anomalies, setAnomalies] = useState<{ anomalies: Anomaly[]; narrative?: string } | null>(null);
  const [from, setFrom] = useState(monthStart()), [to, setTo] = useState(today()), [flux, setFlux] = useState<Flux | null>(null);
  const [bankId, setBankId] = useState(''), [recon, setRecon] = useState<Reconcile | null>(null);
  const [file, setFile] = useState<File | null>(null);

  useEffect(() => {
    setMetrics(null); setChecklist(null); setAnomalies(null); setFlux(null); setRecon(null);
    Promise.all([api<Metrics>(`${base}/ai/metrics`), api<Period[]>(`${base}/periods`), api<Account[]>(`${base}/accounts`)])
      .then(([m, p, a]) => { setMetrics(m); setPeriods(p); setAccounts(a); })
      .catch(e => setError(e instanceof Error ? e.message : 'Could not load AI insights.'));
  }, [companyId]);
  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : 'Unexpected error'); } finally { setBusy(false); }
  }

  const refreshMetrics = () => run(async () => setMetrics(await api<Metrics>(`${base}/ai/metrics`)));
  const runChecklist = () => run(async () => setChecklist(await api<Checklist>(`${base}/ai/close-checklist?narrative=${enabled}${periodId ? `&periodId=${periodId}` : ''}`)));
  const scan = () => run(async () => setAnomalies(await api<{ anomalies: Anomaly[]; narrative?: string }>(`${base}/ai/anomalies?narrative=${enabled}`)));
  const explain = () => run(async () => {
    if (from > to) throw new Error("'From' must be on or before 'To'.");
    setFlux(await api<Flux>(`${base}/ai/flux?from=${from}&to=${to}&narrative=${enabled}`));
  });
  const suggest = () => run(async () => { if (!bankId) throw new Error('Select a bank account first.'); setRecon(await post<Reconcile>(`${base}/ai/reconcile-suggest`, { bankAccountId: Number(bankId) })); });
  const match = (lineId: number, paymentId: number) => run(async () => {
    await post(`${base}/bank/statement-lines/${lineId}/match`, { paymentId });
    setRecon(r => r && { ...r, suggestions: r.suggestions.filter(s => s.lineId !== lineId) });
    setMessage('Matched.');
  });
  const categorise = () => run(async () => {
    if (!bankId) throw new Error('Select a bank account first.');
    const r = await post<{ proposed: number; skipped: number; message?: string }>(`${base}/ai/categorise-bank-lines`, { bankAccountId: Number(bankId) });
    await onProposals();
    setMessage(r.proposed ? `${r.proposed} journal proposal(s) added to "Awaiting your review" below${r.skipped ? `; ${r.skipped} line(s) skipped as unclear` : ''}.` : r.message || 'No lines could be categorised confidently.');
  });
  const upload = () => run(async () => {
    if (!file) throw new Error('Choose a PDF, TXT or CSV file first.');
    const form = new FormData();
    form.append('file', file);
    await apiUpload(`${base}/ai/extract-bill-file`, form);
    setFile(null); await onProposals();
    setMessage('Bill extracted and added to "Awaiting your review" below. Nothing is posted until you accept it and a different person approves it.');
  });

  useToastFeed(error, message);
  const bankAccounts = accounts.filter(a => a.accountType === 'ASSET');
  const aiOnly = !enabled ? ' (AI provider not configured)' : '';
  const status = (s: string) => s === 'DONE' ? 'posted' : s === 'TODO' ? 'draft' : 'chip';

  return <>
    <section className="panel below">
      <div className="panelhead"><div><h2><TrendingUp size={16} /> Business metrics</h2><p>Computed from posted journals and open invoices; no AI estimates</p></div><button className="secondary" disabled={busy} onClick={refreshMetrics}>Refresh</button></div>
      {metrics ? <>
        <Bars series={metrics.series} />
        <p className="hint">Last 12 months: <span style={{ color: 'var(--primary)' }}>■</span> revenue <span style={{ color: 'var(--muted)' }}>■</span> expenses</p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 10, marginBottom: 12 }}>
          <Stat name="Cash balance" value={money(metrics.cashBalance)} hint={metrics.cashBasis} />
          <Stat name="Monthly net burn (3 mo)" value={Number(metrics.monthlyBurn) > 0 ? money(metrics.monthlyBurn) : 'Not burning cash'} />
          <Stat name="Runway" value={metrics.runwayMonths != null ? `${metrics.runwayMonths} months` : '—'} />
          <Stat name="Receivable" value={money(metrics.totalReceivable)} hint={metrics.daysSalesOutstanding != null ? `~${metrics.daysSalesOutstanding} days sales outstanding` : undefined} />
          <Stat name="Payable" value={money(metrics.totalPayable)} />
        </div>
        <div className="grid">
          <div><h3>Receivables ageing</h3><Rows rows={metrics.receivableAgeing} /></div>
          <div><h3>Payables ageing</h3><Rows rows={metrics.payableAgeing} /></div>
        </div>
        {metrics.topCustomersByOutstanding.length > 0 && <><h3>Top customers by outstanding</h3><Rows rows={metrics.topCustomersByOutstanding} /></>}
        {metrics.topVendorsLast90Days.length > 0 && <><h3>Top vendors, last 90 days</h3><Rows rows={metrics.topVendorsLast90Days} /></>}
      </> : <p className="empty">Loading metrics…</p>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><ListChecks size={16} /> Close checklist</h2><p>Live month-end checks; the AI only orders what is still open</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); runChecklist(); }}>
        <label>Period<select value={periodId} onChange={e => setPeriodId(e.target.value)}><option value="">Earliest open period</option>{periods.map(p => <option key={p.id} value={p.id}>{p.name} ({p.status})</option>)}</select></label>
        <button disabled={busy}>Check readiness</button>
      </form>
      {checklist && <>
        <p><strong>{checklist.period.name}</strong> · {checklist.done} of {checklist.total} done {checklist.readyToClose && <span className="posted">Ready to close</span>}</p>
        {checklist.narrative && <p className="hint">{checklist.narrative}</p>}
        <div className="tablewrap"><table><thead><tr><th>Status</th><th>Step</th><th>Detail</th></tr></thead><tbody>
          {checklist.items.map(i => <tr key={i.key}><td><span className={status(i.status)}>{i.status}</span></td><td>{i.title}</td><td>{i.detail}</td></tr>)}</tbody></table></div>
      </>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><Search size={16} /> Anomaly detection</h2><p>Statistical and rule checks on posted data{aiOnly}</p></div><button disabled={busy} onClick={scan}>Scan now</button></div>
      {anomalies && <>
        {anomalies.narrative && <p className="hint">{anomalies.narrative}</p>}
        {!anomalies.anomalies.length && <p className="empty">No anomalies found.</p>}
        {anomalies.anomalies.map(a => <div key={a.code} style={{ marginBottom: 14 }}>
          <strong>{a.title}</strong> <span className="chip">{a.severity}</span> <span className="count">{a.count}</span>
          <p className="hint">{a.detail}</p><Rows rows={a.items} />
        </div>)}
      </>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Variance (flux) commentary</h2><p>Compares with the previous period of equal length; drivers are the largest posted lines{aiOnly}</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); explain(); }}>
        <label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} required /></label>
        <label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} required /></label>
        <button disabled={busy}>Explain variances</button>
      </form>
      {flux && <>
        <p className="hint">{flux.period.from} to {flux.period.to} vs {flux.comparePeriod.from} to {flux.comparePeriod.to}</p>
        {flux.summary && <p>{flux.summary}</p>}
        <div className="tablewrap"><table><thead><tr><th></th><th className="right">Current</th><th className="right">Previous</th><th className="right">Change</th></tr></thead><tbody>
          {(['income', 'expenses', 'netProfit'] as const).map(k => <tr key={k}><td>{label(k)}</td><td className="right">{money(flux.totals[k].current)}</td><td className="right">{money(flux.totals[k].previous)}</td><td className="right">{money(flux.totals[k].change)}</td></tr>)}</tbody></table></div>
        {!flux.rows.length && <p className="empty">No account moved by 10% and ₹5,000 or more.</p>}
        {flux.rows.length > 0 && <div className="tablewrap"><table><thead><tr><th>Account</th><th className="right">Current</th><th className="right">Previous</th><th className="right">Change</th><th>Commentary and drivers</th></tr></thead><tbody>
          {flux.rows.map(r => <tr key={r.accountId}>
            <td>{r.code} — {r.name}</td><td className="right">{money(r.current)}</td><td className="right">{money(r.previous)}</td>
            <td className="right"><span className={r.favourable ? 'posted' : 'draft'}>{money(r.change)}{r.changePercent != null ? ` (${r.changePercent}%)` : ''}</span></td>
            <td>{r.commentary && <p>{r.commentary}</p>}<span className="muted">{r.drivers.map(d => `${je(d.journalId)} ${money(d.amount)}`).join(' · ') || 'No posted lines in the period'}</span></td></tr>)}</tbody></table></div>}
      </>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><Landmark size={16} /> Bank AI</h2><p>Match suggestions use exact amounts you can verify; categorisation creates draft journals for review{aiOnly}</p></div></div>
      <form className="accountform" onSubmit={e => e.preventDefault()}>
        <label>Bank account<select value={bankId} onChange={e => { setBankId(e.target.value); setRecon(null); }}><option value="">Select</option>{bankAccounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <button type="button" disabled={busy || !bankId} onClick={suggest}>Suggest matches</button>
        <button type="button" className="secondary" disabled={busy || !bankId || !enabled} onClick={categorise}>Categorise unexplained lines</button>
      </form>
      {recon && <>
        {recon.note && <p className="hint">{recon.note}</p>}
        {!recon.suggestions.length && <p className="empty">No match suggestions. {recon.unmatchedWithoutCandidate} line(s) have no candidate payment.</p>}
        {recon.suggestions.length > 0 && <div className="tablewrap"><table><thead><tr><th>Statement line</th><th className="right">Amount</th><th>Suggestion</th><th></th></tr></thead><tbody>
          {recon.suggestions.map(s => <tr key={s.lineId}>
            <td>{s.txnDate}<br />{s.description}</td><td className="right">{money(s.amount)}</td>
            <td><span className="chip">{s.confidence}</span> {s.reason}
              {s.nearMisses.length > 0 && <div className="muted">Near amounts: {s.nearMisses.map(n => `#${n.paymentId} ${money(n.amount)} (off by ${money(n.difference)})`).join('; ')}</div>}</td>
            <td>{s.recommendedPaymentId != null && <button className="small" disabled={busy} onClick={() => match(s.lineId, s.recommendedPaymentId!)}>Match #{s.recommendedPaymentId}</button>}</td></tr>)}</tbody></table></div>}
      </>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><Upload size={16} /> Capture a bill from a file</h2><p>PDF with selectable text, TXT or CSV, up to 1 MB. Scanned images are not supported.</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); upload(); }}>
        <label>File<input type="file" accept=".pdf,.txt,.csv" onChange={e => setFile(e.target.files?.[0] ?? null)} /></label>
        <button disabled={busy || !file || !enabled}>Extract bill</button>
      </form>
    </section>
  </>;
}
