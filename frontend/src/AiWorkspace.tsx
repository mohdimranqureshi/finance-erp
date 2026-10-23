import { useEffect, useState } from 'react';
import { Bot, Check, Sparkles, X } from 'lucide-react';
import { api, money } from './api';

type Issue = { code: string; message: string };
type Proposal = { id: number; kind: 'BILL_EXTRACTION' | 'JOURNAL_DRAFT'; status: string; payload: any; warnings: Issue[]; blockers: Issue[]; confidence?: number | null; model: string; resultType?: string | null; resultId?: number | null };
type Status = { enabled: boolean; mode: string; model: string; message: string };
type Finding = { severity: string; code: string; title: string; detail: string; count: number };
type Party = { id: number; name: string; partyType: string };
type Answer = { answer: string; figuresUsed: string[]; notice: string };

const post = <T,>(url: string, body?: unknown) => api<T>(url, { method: 'POST', body: JSON.stringify(body ?? {}) });

export default function AiWorkspace({ companyId, onChanged }: { companyId: number; onChanged: () => Promise<void> }) {
  const base = `/api/v1/companies/${companyId}`;
  const [status, setStatus] = useState<Status | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const [question, setQuestion] = useState(''), [answer, setAnswer] = useState<Answer | null>(null);
  const [findings, setFindings] = useState<Finding[] | null>(null), [narrative, setNarrative] = useState(''), [wantNarrative, setWantNarrative] = useState(false);
  const [docText, setDocText] = useState(''), [instruction, setInstruction] = useState('');
  const [proposals, setProposals] = useState<Proposal[]>([]), [vendors, setVendors] = useState<Party[]>([]);
  const [fix, setFix] = useState<Record<number, { vendorId?: string; number?: string; date?: string }>>({});

  async function load() {
    const [s, p, v] = await Promise.all([api<Status>('/api/v1/ai/status'), api<Proposal[]>(`${base}/ai/proposals?status=PROPOSED`), api<Party[]>(`${base}/parties?type=VENDOR`)]);
    setStatus(s); setProposals(p); setVendors(v);
  }
  useEffect(() => { load().catch(e => setError(e.message)); }, [companyId]);
  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : 'Unexpected error'); } finally { setBusy(false); }
  }

  const ask = () => run(async () => setAnswer(await post<Answer>(`${base}/ai/ask`, { question })));
  const review = () => run(async () => {
    const r = await api<{ findings: Finding[]; narrative?: string }>(`${base}/ai/review?narrative=${wantNarrative}`);
    setFindings(r.findings); setNarrative(r.narrative || '');
  });
  const extract = () => run(async () => { await post(`${base}/ai/extract-bill`, { text: docText }); setDocText(''); await load(); setMessage('Bill extracted. Review it below before accepting.'); });
  const draft = () => run(async () => { await post(`${base}/ai/draft-journal`, { instruction }); setInstruction(''); await load(); setMessage('Journal proposed. Review it below before accepting.'); });
  const accept = (p: Proposal) => run(async () => {
    const f = fix[p.id] || {};
    await post(`${base}/ai/proposals/${p.id}/accept`, { vendorId: f.vendorId ? Number(f.vendorId) : undefined, vendorInvoiceNumber: f.number || undefined, billDate: f.date || undefined });
    await load(); await onChanged(); setMessage('Accepted as a DRAFT. It still needs independent approval before it reaches the ledger.');
  });
  const reject = (p: Proposal) => run(async () => { await post(`${base}/ai/proposals/${p.id}/reject`); await load(); });
  const setFixField = (id: number, patch: { vendorId?: string; number?: string; date?: string }) => setFix(prev => ({ ...prev, [id]: { ...prev[id], ...patch } }));
  const disabled = busy || !status?.enabled;

  const card = (p: Proposal) => {
    const f = fix[p.id] || {};
    const codes = p.blockers.map(b => b.code);
    return <div key={p.id} className="alert" style={{ marginBottom: 12 }}>
      <strong>{p.kind === 'BILL_EXTRACTION' ? `Purchase bill ${p.payload.vendorInvoiceNumber || '(no number)'}` : `Journal: ${p.payload.description}`}</strong>
      {p.confidence != null && <span className="chip" style={{ marginLeft: 8 }}>confidence {Math.round(Number(p.confidence) * 100)}%</span>}
      {p.kind === 'BILL_EXTRACTION' && <p>{p.payload.vendorName || 'Unknown vendor'} · {p.payload.billDate || 'no date'} · computed total {money(p.payload.computedTotal)}{p.payload.tdsSection ? ` · TDS ${p.payload.tdsSection} @ ${p.payload.tdsRate}% (verify)` : ''}</p>}
      <div className="tablewrap"><table><thead>{p.kind === 'BILL_EXTRACTION'
        ? <tr><th>Description</th><th>HSN/SAC</th><th className="right">Qty</th><th className="right">Price</th><th className="right">GST %</th></tr>
        : <tr><th>Account</th><th className="right">Debit</th><th className="right">Credit</th></tr>}</thead><tbody>
        {p.payload.lines.map((l: any, i: number) => p.kind === 'BILL_EXTRACTION'
          ? <tr key={i}><td>{l.description}</td><td className="mono">{l.hsnSac}</td><td className="right">{l.quantity}</td><td className="right">{money(l.unitPrice)}</td><td className="right">{l.gstRate}</td></tr>
          : <tr key={i}><td>{l.accountCode} — {l.accountName}</td><td className="right">{money(l.debit)}</td><td className="right">{money(l.credit)}</td></tr>)}</tbody></table></div>
      {p.warnings.map((w, i) => <p key={i} className="hint">Check: {w.message}</p>)}
      {p.blockers.map((b, i) => <p key={i} className="hint"><strong>Fix: </strong>{b.message}</p>)}
      <div className="accountform">
        {codes.includes('VENDOR') && <label>Vendor<select value={f.vendorId || ''} onChange={e => setFixField(p.id, { vendorId: e.target.value })}><option value="">Select</option>{vendors.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>}
        {codes.includes('INVOICE_NUMBER') && <label>Invoice number<input value={f.number || ''} onChange={e => setFixField(p.id, { number: e.target.value })} /></label>}
        {codes.includes('INVOICE_DATE') && <label>Invoice date<input type="date" value={f.date || ''} onChange={e => setFixField(p.id, { date: e.target.value })} /></label>}
        <button disabled={busy} onClick={() => accept(p)}><Check size={15} />Accept as draft</button>
        <button className="secondary" disabled={busy} onClick={() => reject(p)}><X size={15} />Reject</button>
      </div>
    </div>;
  };

  return <>
    <div className="eyebrow" style={{ marginTop: 24 }}>AI WORKSPACE</div>
    {error && <div className="alert error">{error}</div>}{message && <div className="alert success">{message}</div>}
    {status && <div className="alert"><Bot size={15} /> {status.enabled ? `AI on (${status.model}). ` : 'AI provider not configured. '}{status.message}</div>}

    <section className="panel below">
      <div className="panelhead"><div><h2><Sparkles size={16} /> Ask Finora</h2><p>Answers come only from this company's current P&L, balance sheet, GST and open items</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); ask(); }}>
        <label>Question<input value={question} onChange={e => setQuestion(e.target.value)} maxLength={500} placeholder="Which customers are overdue the most?" required /></label>
        <button disabled={disabled}>Ask</button>
      </form>
      {answer && <div className="alert success"><p>{answer.answer}</p>{answer.figuresUsed.length > 0 && <p className="hint">Figures used: {answer.figuresUsed.join('; ')}</p>}<p className="hint">{answer.notice}</p></div>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Close review</h2><p>Automated checks run without AI; the AI summary only prioritises the findings</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); review(); }}>
        <label><span>AI summary</span><input type="checkbox" checked={wantNarrative} disabled={!status?.enabled} onChange={e => setWantNarrative(e.target.checked)} /></label>
        <button disabled={busy}>Run review</button>
      </form>
      {narrative && <div className="alert success">{narrative}</div>}
      {findings && <div className="tablewrap"><table><thead><tr><th>Severity</th><th>Finding</th><th>Detail</th><th className="right">Items</th></tr></thead><tbody>
        {findings.map(f => <tr key={f.code}><td><span className="chip">{f.severity}</span></td><td>{f.title}</td><td>{f.detail}</td><td className="right">{f.count}</td></tr>)}
        {!findings.length && <tr><td colSpan={4} className="empty">No issues found.</td></tr>}</tbody></table></div>}
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Capture a vendor bill</h2><p>Paste text from an invoice or email. GSTIN, HSN/SAC, GST slabs, duplicates and totals are validated by the server</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); extract(); }}>
        <label>Invoice text<textarea value={docText} onChange={e => setDocText(e.target.value)} rows={5} required /></label>
        <button disabled={disabled}>Extract bill</button>
      </form>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Draft a journal from words</h2><p>Uses only your chart of accounts and must balance; the result is a proposal, not a posting</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); draft(); }}>
        <label>Instruction<input value={instruction} onChange={e => setInstruction(e.target.value)} maxLength={1000} placeholder="Record Rs 50,000 office rent paid from bank" required /></label>
        <button disabled={disabled}>Propose journal</button>
      </form>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>Awaiting your review</h2><p>Accepting creates a DRAFT; a different person must still approve it</p></div><span className="count">{proposals.length}</span></div>
      {proposals.map(card)}{!proposals.length && <p className="empty">No pending AI proposals.</p>}
    </section>
  </>;
}
