import { useEffect, useState } from 'react';
import { Check, Upload, X } from 'lucide-react';
import { api, apiUpload, money } from './api';

type Issue = { code: string; message: string };
type Proposal = { id: number; payload: any; warnings: Issue[]; blockers: Issue[]; confidence?: number | null; sourceFile?: string };
type Party = { id: number; name: string };
type Fix = { partyId?: string; number?: string; date?: string };

const backdrop: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,25,22,.55)', zIndex: 1000, display: 'grid', placeItems: 'center', padding: 16 };
const dialog: React.CSSProperties = { background: '#fff', borderRadius: 14, width: 'min(860px, 100%)', maxHeight: '90vh', overflowY: 'auto', padding: 20, boxShadow: '0 20px 60px rgba(0,0,0,.3)' };

export default function InvoiceUpload({ companyId, kind, parties, busy, run, onCreated }: {
  companyId: number; kind: 'sale' | 'bill'; parties: Party[]; busy: boolean;
  run: (fn: () => Promise<void>) => Promise<void>; onCreated: (message: string) => Promise<void>;
}) {
  const base = `/api/v1/companies/${companyId}`;
  const sale = kind === 'sale';
  const title = sale ? 'Upload GST sales invoice' : 'Upload purchase bill';
  const [open, setOpen] = useState(false), [enabled, setEnabled] = useState(false), [file, setFile] = useState<File | null>(null);
  const [proposal, setProposal] = useState<Proposal | null>(null), [fix, setFix] = useState<Fix>({});

  useEffect(() => { api<{ enabled: boolean }>('/api/v1/ai/status').then(s => setEnabled(s.enabled)).catch(() => undefined); }, []);
  useEffect(() => { close(); }, [companyId]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  function close() { setOpen(false); setProposal(null); setFile(null); setFix({}); }

  const upload = () => run(async () => {
    if (!file) throw new Error('Choose a PDF, TXT or CSV file first.');
    const form = new FormData();
    form.append('file', file);
    setProposal(await apiUpload<Proposal>(`${base}/ai/${sale ? 'extract-invoice-file' : 'extract-bill-file'}`, form));
    setFix({}); setFile(null);
  });
  const accept = () => run(async () => {
    if (!proposal) return;
    const partyId = fix.partyId ? Number(fix.partyId) : undefined;
    await api(`${base}/ai/proposals/${proposal.id}/accept`, {
      method: 'POST',
      body: JSON.stringify({ [sale ? 'customerId' : 'vendorId']: partyId, vendorInvoiceNumber: fix.number || undefined, billDate: fix.date || undefined })
    });
    close();
    await onCreated(sale ? 'Draft invoice created from the upload. Check it below, then issue it.' : 'Draft purchase bill created from the upload. Check it below, then book it.');
  });
  const reject = () => run(async () => {
    if (!proposal) return;
    await api(`${base}/ai/proposals/${proposal.id}/reject`, { method: 'POST', body: '{}' });
    setProposal(null);
  });

  const p = proposal?.payload;
  const codes = proposal?.blockers.map(b => b.code) ?? [];
  return <>
    <button type="button" className="secondary" style={{ marginBottom: 14 }} onClick={() => setOpen(true)}><Upload size={15} />{title}</button>
    {open && <div style={backdrop} onMouseDown={e => { if (e.target === e.currentTarget && !busy) close(); }}>
      <div style={dialog} role="dialog" aria-modal="true" aria-label={title}>
    <div className="panelhead">
      <div><h2><Upload size={16} /> {title}</h2><p>PDF with selectable text, TXT or CSV, up to 1 MB. Nothing is posted until you create the draft and a different person approves it.</p></div>
      <button type="button" className="small secondary" aria-label="Close" disabled={busy} onClick={close}><X size={14} /></button>
    </div>
    {!proposal && <>
      <form className="accountform" onSubmit={e => { e.preventDefault(); upload(); }}>
        <label>File<input type="file" accept=".pdf,.txt,.csv" onChange={e => setFile(e.target.files?.[0] ?? null)} /></label>
        <button disabled={busy || !file || !enabled}><Upload size={15} />{busy ? 'Extracting…' : 'Extract details'}</button>
      </form>
      {!enabled && <p className="hint">Upload needs the AI provider to be configured. You can still enter the {sale ? 'invoice' : 'bill'} manually in the form.</p>}
    </>}
    {proposal && p && <div>
      <strong>{sale ? 'Sales invoice' : `Purchase bill ${p.vendorInvoiceNumber || '(no number)'}`}</strong>
      {proposal.sourceFile && <span className="muted"> · {proposal.sourceFile}</span>}
      {proposal.confidence != null && <span className="chip" style={{ marginLeft: 8 }}>confidence {Math.round(Number(proposal.confidence) * 100)}%</span>}
      <p>{(sale ? p.customerName : p.vendorName) || (sale ? 'Unknown customer' : 'Unknown vendor')}{(sale ? p.customerGstin : p.vendorGstin) ? ` (${sale ? p.customerGstin : p.vendorGstin})` : ''} · {(sale ? p.invoiceDate : p.billDate) || 'no date'}{p.dueDate ? ` · due ${p.dueDate}` : ''} · computed total {money(p.computedTotal)}{!sale && p.tdsSection ? ` · TDS ${p.tdsSection} @ ${p.tdsRate}% (verify)` : ''}</p>
      <div className="tablewrap"><table><thead><tr><th>Description</th><th>HSN/SAC</th><th className="right">Qty</th><th className="right">Price</th><th className="right">GST %</th></tr></thead><tbody>
        {p.lines.map((l: any, i: number) => <tr key={i}><td>{l.description}</td><td className="mono">{l.hsnSac}</td><td className="right">{l.quantity}</td><td className="right">{money(l.unitPrice)}</td><td className="right">{l.gstRate}</td></tr>)}</tbody></table></div>
      {proposal.warnings.map((w, i) => <p key={i} className="hint">Check: {w.message}</p>)}
      {proposal.blockers.map((b, i) => <p key={i} className="hint"><strong>Fix: </strong>{b.message}</p>)}
      <div className="accountform">
        {(codes.includes('VENDOR') || codes.includes('CUSTOMER')) && <label>{sale ? 'Customer' : 'Vendor'}<select value={fix.partyId || ''} onChange={e => setFix(f => ({ ...f, partyId: e.target.value }))}><option value="">Select</option>{parties.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</select></label>}
        {codes.includes('INVOICE_NUMBER') && <label>Invoice number<input value={fix.number || ''} onChange={e => setFix(f => ({ ...f, number: e.target.value }))} /></label>}
        {codes.includes('INVOICE_DATE') && <label>Invoice date<input type="date" value={fix.date || ''} onChange={e => setFix(f => ({ ...f, date: e.target.value }))} /></label>}
        <button type="button" disabled={busy} onClick={accept}><Check size={15} />Create draft {sale ? 'invoice' : 'bill'}</button>
        <button type="button" className="secondary" disabled={busy} onClick={reject}><X size={15} />Discard</button>
      </div>
      <p className="hint">Closing this window keeps the extraction under "Awaiting your review" on the AI assistant page.</p>
    </div>}
      </div>
    </div>}
  </>;
}
