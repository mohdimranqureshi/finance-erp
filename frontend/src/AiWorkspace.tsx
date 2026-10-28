import { useEffect, useRef, useState } from 'react';
import { Bot, Check, ClipboardCheck, Send, X } from 'lucide-react';
import { api, money } from './api';
import { useToastFeed } from './toast';

type Issue = { code: string; message: string };
type Proposal = { id: number; kind: 'BILL_EXTRACTION' | 'JOURNAL_DRAFT'; status: string; payload: any; warnings: Issue[]; blockers: Issue[]; confidence?: number | null; model: string; resultType?: string | null; resultId?: number | null };
type Status = { enabled: boolean; mode: string; model: string; message: string };
type Finding = { severity: string; code: string; title: string; detail: string; count: number };
type Party = { id: number; name: string; partyType: string };
type Answer = { answer: string; figuresUsed: string[]; notice: string };
type Msg = { role: 'user' | 'assistant'; text: string; error?: boolean; pending?: boolean; figures?: string[]; notice?: string; findings?: Finding[] };
type Mode = 'ask' | 'journal' | 'bill';

const MODES: Record<Mode, { label: string; placeholder: string }> = {
  ask: { label: 'Ask a question', placeholder: 'Ask about your P&L, balance sheet, GST, receivables or payables…' },
  journal: { label: 'Draft a journal', placeholder: 'Record Rs 50,000 office rent paid from bank' },
  bill: { label: 'Capture a vendor bill', placeholder: 'Paste the text of a vendor invoice or email…' },
};
const SUGGESTIONS = ['What is our profit this financial year?', 'Which customers are overdue the most?', 'How much GST do we owe this period?', 'What are our biggest expenses?'];

const post = <T,>(url: string, body?: unknown) => api<T>(url, { method: 'POST', body: JSON.stringify(body ?? {}) });

export default function AiWorkspace({ companyId, onChanged, compact = false, onOpenFull }: { companyId: number; onChanged: () => Promise<void>; compact?: boolean; onOpenFull?: () => void }) {
  const base = `/api/v1/companies/${companyId}`;
  const [status, setStatus] = useState<Status | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const [messages, setMessages] = useState<Msg[]>([]), [mode, setMode] = useState<Mode>('ask'), [input, setInput] = useState('');
  const threadEnd = useRef<HTMLDivElement>(null);
  const [proposals, setProposals] = useState<Proposal[]>([]), [vendors, setVendors] = useState<Party[]>([]);
  const [fix, setFix] = useState<Record<number, { vendorId?: string; number?: string; date?: string }>>({});

  async function load() {
    const [s, p, v] = await Promise.all([api<Status>('/api/v1/ai/status'), api<Proposal[]>(`${base}/ai/proposals?status=PROPOSED`), api<Party[]>(`${base}/parties?type=VENDOR`)]);
    setStatus(s); setProposals(p); setVendors(v);
  }
  useEffect(() => { setMessages([]); load().catch(e => setError(e.message)); }, [companyId]);
  useEffect(() => { threadEnd.current?.scrollIntoView({ block: 'end' }); }, [messages]);
  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : 'Unexpected error'); } finally { setBusy(false); }
  }

  // Appends the user's turn plus a placeholder reply, then swaps the placeholder for the real result.
  async function converse(userText: string, produce: () => Promise<Msg>) {
    setBusy(true); setError(''); setMessage('');
    setMessages(prev => [...prev, { role: 'user', text: userText }, { role: 'assistant', text: 'Working on it…', pending: true }]);
    let reply: Msg;
    try { reply = await produce(); } catch (e) { reply = { role: 'assistant', text: e instanceof Error ? e.message : 'Unexpected error', error: true }; }
    setMessages(prev => [...prev.slice(0, -1), reply]);
    setBusy(false);
  }
  const send = (override?: string) => {
    const text = (override ?? input).trim();
    if (!text || busy || !status?.enabled) return;
    const m = override ? 'ask' : mode;
    if (!override) setInput('');
    return converse(m === 'bill' ? 'Capture this vendor bill:\n' + text : text, async () => {
      if (m === 'ask') {
        const a = await post<Answer>(`${base}/ai/ask`, { question: text });
        return { role: 'assistant', text: a.answer, figures: a.figuresUsed, notice: a.notice };
      }
      await post(`${base}/ai/${m === 'bill' ? 'extract-bill' : 'draft-journal'}`, m === 'bill' ? { text } : { instruction: text });
      await load();
      return { role: 'assistant', text: `${m === 'bill' ? 'I extracted the bill' : 'I drafted the journal'} and added it to "Awaiting your review" ${compact ? 'on the AI assistant page' : 'below'}. Nothing reaches the ledger until you accept it and a different person approves it.` };
    });
  };
  const closeReview = () => {
    if (busy) return;
    return converse('Run the close review', async () => {
      const r = await api<{ findings: Finding[]; narrative?: string }>(`${base}/ai/review?narrative=${!!status?.enabled}`);
      const text = r.narrative || (r.findings.length ? `${r.findings.length} area(s) need attention before close.` : 'No issues found. The books look ready to close.');
      return { role: 'assistant', text, findings: r.findings };
    });
  };
  const accept = (p: Proposal) => run(async () => {
    const f = fix[p.id] || {};
    await post(`${base}/ai/proposals/${p.id}/accept`, { vendorId: f.vendorId ? Number(f.vendorId) : undefined, vendorInvoiceNumber: f.number || undefined, billDate: f.date || undefined });
    await load(); await onChanged(); setMessage('Accepted as a DRAFT. It still needs independent approval before it reaches the ledger.');
  });
  const reject = (p: Proposal) => run(async () => { await post(`${base}/ai/proposals/${p.id}/reject`); await load(); });
  const setFixField = (id: number, patch: { vendorId?: string; number?: string; date?: string }) => setFix(prev => ({ ...prev, [id]: { ...prev[id], ...patch } }));

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

  useToastFeed(error, message);
  return <>
    {status && !status.enabled && <div className="alert"><Bot size={15} /> AI provider not configured. {status.message}</div>}

    <section className="aichat">
      <div className="aichat-head">
        <div className="avatar"><Bot size={19} /></div>
        <div><h2>Finora AI assistant</h2><p>{status?.enabled ? `Model ${status.model}. ` : ''}Answers come only from this company's P&amp;L, balance sheet, GST and open items. AI proposes; people approve.</p></div>
      </div>
      <div className="aichat-thread">
        {!messages.length && <div className="aichat-empty">
          <Bot size={30} /><h3>What would you like to know?</h3>
          <p>Ask about your numbers, draft a journal from plain words, or capture a vendor bill.</p>
          <div className="aichat-suggest">{SUGGESTIONS.map(s => <button key={s} disabled={busy || !status?.enabled} onClick={() => send(s)}>{s}</button>)}</div>
        </div>}
        {messages.map((m, i) => <div key={i} className={`msg ${m.role}${m.error ? ' error' : ''}${m.pending ? ' pending' : ''}`}>
          {m.text}
          {m.figures && m.figures.length > 0 && <p className="meta">Figures used: {m.figures.join('; ')}</p>}
          {m.notice && <p className="meta">{m.notice}</p>}
          {m.findings && m.findings.length > 0 && <div className="tablewrap"><table><thead><tr><th>Severity</th><th>Finding</th><th>Detail</th><th className="right">Items</th></tr></thead><tbody>
            {m.findings.map(f => <tr key={f.code}><td><span className="chip">{f.severity}</span></td><td>{f.title}</td><td>{f.detail}</td><td className="right">{f.count}</td></tr>)}</tbody></table></div>}
        </div>)}
        <div ref={threadEnd} />
      </div>
      <form className="aichat-composer" onSubmit={e => { e.preventDefault(); send(); }}>
        <div className="aichat-modes">
          {(Object.keys(MODES) as Mode[]).map(k => <button type="button" key={k} className={mode === k ? 'on' : ''} onClick={() => setMode(k)}>{MODES[k].label}</button>)}
          <button type="button" className="spacer" disabled={busy} onClick={closeReview}><ClipboardCheck size={14} />Run close review</button>
        </div>
        <div className="aichat-input">
          <textarea value={input} rows={mode === 'bill' ? 4 : 1} maxLength={mode === 'ask' ? 500 : mode === 'journal' ? 1000 : 20000} placeholder={MODES[mode].placeholder} disabled={!status?.enabled}
            onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && mode !== 'bill') { e.preventDefault(); send(); } }} />
          <button disabled={busy || !input.trim() || !status?.enabled}><Send size={15} />Send</button>
        </div>
        <p className="aichat-foot">Each message is answered independently from your current books; earlier messages are not remembered. GSTIN, HSN/SAC, GST slabs, duplicates and totals are validated by the server.</p>
      </form>
    </section>

    {compact ? (proposals.length > 0 && <div className="aipopup-review"><span>{proposals.length} AI proposal{proposals.length > 1 ? 's' : ''} awaiting your review</span><button className="small" onClick={onOpenFull}>Review</button></div>) : <section className="panel below">
      <div className="panelhead"><div><h2>Awaiting your review</h2><p>Accepting creates a DRAFT; a different person must still approve it</p></div><span className="count">{proposals.length}</span></div>
      {proposals.map(card)}{!proposals.length && <p className="empty">No pending AI proposals.</p>}
    </section>}
  </>;
}
