import { useCallback, useEffect, useState } from 'react';
import { FileUp, Mail, Trash2 } from 'lucide-react';
import { api, money } from '../api';
import { Panel, Stat, Table } from '../ui';
import type { Shell } from '../types';

type ImportRow = { id: number; returnPeriod: string; lineCount: number; skippedDocuments: number; importedAt: string };
type Summary = ImportRow & {
  counts: Record<string, number>; itcMatched: number; itcAtRisk: number; itcNotRecordedInBooks: number;
  itcToReverse: number; openExceptions: number;
};
type Item = {
  id: number; status: string; reasons: string; resolution?: string | null; resolutionNote?: string | null;
  docType?: string | null; supplierName?: string | null; supplierGstin?: string | null; portalNumber?: string | null;
  portalTax?: number | null; billInvoiceNumber?: string | null; billTax?: number | null; noteNumber?: string | null;
  noteTax?: number | null; vendorName?: string | null;
};
type FollowUp = { vendorId: number; vendorName: string; gstin: string; email?: string | null; documents: number; itcAtRisk: number; documentNumbers: string };
type Email = { to?: string | null; subject: string; body: string; generatedBy: string };
type Proposal = { id: number; blockers: { code: string; message: string }[]; warnings: { code: string; message: string }[] };

const STATUS_LABEL: Record<string, string> = {
  MATCHED: 'Matched', MISMATCH: 'Mismatch', MISSING_IN_BOOKS: 'In 2B, not in books', MISSING_IN_2B: 'In books, not in 2B',
  ITC_NOT_AVAILABLE: 'ITC not available', REVERSE_CHARGE: 'Reverse charge', AMENDMENT_REVIEW: 'Amendment',
};
const FILTERS = ['', 'MISMATCH', 'MISSING_IN_BOOKS', 'MISSING_IN_2B', 'ITC_NOT_AVAILABLE', 'AMENDMENT_REVIEW', 'MATCHED'];
const periodLabel = (p: string) => `${p.slice(0, 2)}/${p.slice(2)}`;

export default function Gstr2bView({ companyId, shell }: { companyId: number | ''; shell: Shell }) {
  const [imports, setImports] = useState<ImportRow[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [followUps, setFollowUps] = useState<FollowUp[]>([]);
  const [filter, setFilter] = useState('');
  const [email, setEmail] = useState<Email | null>(null);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const base = `/api/v1/companies/${companyId}/gstr2b`;

  const loadImports = useCallback(async () => {
    if (companyId === '') return;
    const rows = await api<ImportRow[]>(`${base}/imports`);
    setImports(rows);
    setSelectedId(prev => (prev && rows.some(r => r.id === prev) ? prev : rows[0]?.id ?? null));
  }, [base, companyId]);

  const loadDetail = useCallback(async (id: number, status: string) => {
    const [s, i, f] = await Promise.all([
      api<Summary>(`${base}/imports/${id}`),
      api<Item[]>(`${base}/imports/${id}/items${status ? `?status=${status}` : ''}`),
      api<FollowUp[]>(`${base}/imports/${id}/vendor-follow-ups`),
    ]);
    setSummary(s); setItems(i); setFollowUps(f);
  }, [base]);

  useEffect(() => { loadImports().catch(e => shell.notify(e.message)); }, [loadImports]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (selectedId === null) { setSummary(null); setItems([]); setFollowUps([]); return; }
    loadDetail(selectedId, filter).catch(e => shell.notify(e.message));
  }, [selectedId, filter, loadDetail]); // eslint-disable-line react-hooks/exhaustive-deps

  const upload = (file: File | undefined) => file && shell.run(async () => {
    if (file.size > 20 * 1024 * 1024) throw Error('That file is too large for a GSTR-2B JSON.');
    let json: unknown;
    try { json = JSON.parse(await file.text()); } catch { throw Error('That file is not valid JSON. Download the GSTR-2B JSON from the GST portal.'); }
    const created = await api<Summary>(`${base}/imports`, { method: 'POST', body: JSON.stringify(json) });
    await loadImports(); setSelectedId(created.id);
    shell.notify(`Imported GSTR-2B for ${periodLabel(created.returnPeriod)} and reconciled it with your purchase bills.`);
  });

  const rerun = () => shell.run(async () => {
    if (selectedId === null) return;
    await api(`${base}/imports/${selectedId}/reconcile`, { method: 'POST' });
    await loadDetail(selectedId, filter);
    shell.notify('Reconciled again with the latest bills. Your resolutions were kept.');
  });

  const remove = () => shell.run(async () => {
    if (selectedId === null || !window.confirm('Delete this import so it can be uploaded again? Resolutions on its items are lost.')) return;
    await api(`${base}/imports/${selectedId}`, { method: 'DELETE' });
    await loadImports();
  });

  const resolve = (item: Item, resolution: string) => shell.run(async () => {
    const note = resolution === 'IGNORED' ? window.prompt('Why is this ignored? (optional)') ?? undefined : undefined;
    await api(`${base}/items/${item.id}/resolve`, { method: 'POST', body: JSON.stringify({ resolution, note }) });
    if (selectedId !== null) await loadDetail(selectedId, filter);
  });

  const draftBill = (item: Item) => shell.run(async () => {
    const p = await api<Proposal>(`${base}/items/${item.id}/draft-bill`, { method: 'POST' });
    setProposal(p);
    shell.notify('Bill proposal created. Review it under AI assistant → proposals, then accept it to create a draft bill.');
  });

  const draftEmail = (f: FollowUp, ai: boolean) => shell.run(async () => {
    if (selectedId === null) return;
    setEmail(await api<Email>(`${base}/imports/${selectedId}/vendor-follow-ups/${f.vendorId}/email${ai ? '?ai=true' : ''}`, { method: 'POST' }));
  });

  if (companyId === '') return <p className="empty">Select or create a company first.</p>;

  return <>
    <Panel title="GSTR-2B reconciliation"
      description="Upload the GSTR-2B JSON from the GST portal. It is matched to your purchase bills by supplier GSTIN and invoice number, so you can see ITC at risk before you file. Nothing is posted to the ledger."
      aside={<span className="count">{imports.length} returns imported</span>}>
      <div className="inline" style={{ flexWrap: 'wrap', gap: 8 }}>
        <label className="secondary" style={{ cursor: 'pointer' }}>
          <FileUp size={15} /> Upload GSTR-2B JSON
          <input type="file" accept=".json,application/json" hidden disabled={shell.busy}
            onChange={e => { upload(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
        {imports.length > 0 && <label>Return period
          <select value={selectedId ?? ''} onChange={e => setSelectedId(Number(e.target.value))}>
            {imports.map(i => <option key={i.id} value={i.id}>{periodLabel(i.returnPeriod)}</option>)}</select></label>}
        {selectedId !== null && <>
          <button className="small secondary" disabled={shell.busy} onClick={rerun}>Reconcile again</button>
          <button className="small danger" disabled={shell.busy} onClick={remove}><Trash2 size={13} />Delete import</button></>}
      </div>
    </Panel>

    {summary && <>
      <section className="stats">
        <Stat label="ITC matched" value={money(summary.itcMatched)} note="Claimable, backed by GSTR-2B" />
        <Stat label="ITC at risk" value={money(summary.itcAtRisk)} note="In your books but not (fully) in 2B" />
        <Stat label="Not recorded in books" value={money(summary.itcNotRecordedInBooks)} note="In 2B, no bill entered yet" />
        <Stat label="ITC to reverse" value={money(summary.itcToReverse)} note="Supplier credit notes you have not recorded" />
      </section>
      <p className="hint">{summary.openExceptions} open exception{summary.openExceptions === 1 ? '' : 's'}
        {summary.skippedDocuments > 0 && ` · ${summary.skippedDocuments} documents in sections this app does not reconcile were skipped (for example ISD and imports)`}</p>

      <Panel title="Exceptions" aside={<select aria-label="Filter by status" value={filter} onChange={e => setFilter(e.target.value)}>
        {FILTERS.map(f => <option key={f} value={f}>{f ? STATUS_LABEL[f] + ` (${summary.counts[f] ?? 0})` : 'All'}</option>)}</select>}>
        <Table columns={6} isEmpty={!items.length} empty="Nothing to show for this filter."
          head={<tr><th>Status</th><th>Supplier</th><th>Document</th><th className="right">Tax: books / 2B</th><th>Why</th><th>Action</th></tr>}>
          {items.map(i => <tr key={i.id}>
            <td><span className={i.status === 'MATCHED' ? 'posted' : 'chip'}>{STATUS_LABEL[i.status] ?? i.status}</span>
              {i.resolution && <><br /><small className="muted">{i.resolution.replace(/_/g, ' ').toLowerCase()}</small></>}</td>
            <td>{i.supplierName || i.vendorName || '—'}<br /><small className="mono muted">{i.supplierGstin}</small></td>
            <td className="mono">{i.portalNumber || i.billInvoiceNumber || i.noteNumber}{i.docType && i.docType !== 'INVOICE' && <> <span className="chip">{i.docType.replace('_', ' ').toLowerCase()}</span></>}</td>
            <td className="right">{money(i.billTax ?? i.noteTax ?? 0)} / {money(i.portalTax ?? 0)}</td>
            <td><small>{i.reasons || '—'}</small></td>
            <td>{i.status !== 'MATCHED' && <span className="inline" style={{ flexWrap: 'wrap', gap: 4 }}>
              {(i.status === 'MISSING_IN_BOOKS' || i.status === 'AMENDMENT_REVIEW') && i.docType !== 'CREDIT_NOTE' &&
                <button className="small" disabled={shell.busy} onClick={() => draftBill(i)}>Draft bill</button>}
              <button className="small secondary" disabled={shell.busy} onClick={() => resolve(i, 'ACCEPTED')}>Accept</button>
              {i.status === 'MISSING_IN_2B' && <button className="small secondary" disabled={shell.busy} onClick={() => resolve(i, 'FOLLOW_UP_VENDOR')}>Following up</button>}
              <button className="small secondary" disabled={shell.busy} onClick={() => resolve(i, 'IGNORED')}>Ignore</button>
            </span>}</td>
          </tr>)}
        </Table>
        {proposal && <div className="alert" role="status">
          <strong>Bill proposal #{proposal.id} created.</strong>
          {proposal.blockers.map(b => <div key={b.code}>Needs attention: {b.message}</div>)}
          {proposal.warnings.map(w => <div key={w.code}><small>{w.message}</small></div>)}
        </div>}
      </Panel>

      <Panel title="Suppliers to chase" description="Suppliers whose invoices are in your books but missing from this GSTR-2B, ranked by ITC at risk. Emails are drafts to copy; nothing is sent."
        aside={<span className="count">{followUps.length} suppliers</span>}>
        <Table columns={5} isEmpty={!followUps.length} empty="No suppliers to follow up."
          head={<tr><th>Supplier</th><th>Documents</th><th className="right">ITC at risk</th><th>Invoice numbers</th><th>Action</th></tr>}>
          {followUps.map(f => <tr key={f.vendorId}>
            <td>{f.vendorName}<br /><small className="mono muted">{f.gstin}</small></td><td>{f.documents}</td>
            <td className="right">{money(f.itcAtRisk)}</td><td><small>{f.documentNumbers}</small></td>
            <td><span className="inline"><button className="small" disabled={shell.busy} onClick={() => draftEmail(f, false)}><Mail size={13} />Draft email</button>
              <button className="small secondary" disabled={shell.busy} onClick={() => draftEmail(f, true)}>With AI wording</button></span></td>
          </tr>)}
        </Table>
        {email && <div className="alert" role="status">
          <p><strong>To:</strong> {email.to || 'no email on file'} · <strong>Subject:</strong> {email.subject} <small className="muted">({email.generatedBy})</small></p>
          <pre className="mono" style={{ whiteSpace: 'pre-wrap' }}>{email.body}</pre>
          <button className="small secondary" onClick={() => navigator.clipboard.writeText(`Subject: ${email.subject}\n\n${email.body}`).then(() => shell.notify('Email copied.'))}>Copy</button>
          <button className="small secondary" onClick={() => setEmail(null)}>Close</button>
        </div>}
      </Panel>
    </>}
  </>;
}
