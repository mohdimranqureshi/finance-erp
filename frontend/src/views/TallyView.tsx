import { useState } from 'react';
import { Download, FileUp } from 'lucide-react';
import { apiDownload, apiUpload } from '../api';
import { Panel, Stat, Table } from '../ui';
import type { Shell } from '../types';

type LedgerPlan = {
  ledgerName: string; tallyGroup?: string | null; primaryGroup?: string | null; action: string; accountCode?: string | null;
  accountName?: string | null; accountType?: string | null; partyName?: string | null; partyType?: string | null;
  voucherCount: number; reason?: string | null; warnings?: string[];
};
type Problem = { code: string; message: string; ledgerName?: string; voucher?: string };
type Preview = {
  fileName: string;
  summary: { ledgers: number; partiesToCreate: number; vouchers: number; importable: number; alreadyImported: number; skipped: number; failed: number };
  ledgers: LedgerPlan[];
  vouchers: { total: number; from?: string | null; to?: string | null; byType: { voucherType: string; count: number; importable: number; skipped: number; failed: number }[] };
  openingBalances: { note: string; totalDebit: number; totalCredit: number; ledgers: unknown[] };
  problems: Problem[]; problemCount: number; warnings: string[];
};
type Result = { accountsCreated: number; partiesCreated: number; journalsCreated: number; skipped: number; failed: number; note: string; problems: Problem[] };

const ACTION_LABEL: Record<string, string> = {
  MATCHED_ACCOUNT: 'Existing account', NEW_ACCOUNT: 'New account', MATCHED_PARTY: 'Existing party', NEW_PARTY: 'New party', UNMAPPED: 'Needs mapping',
};
const CHOICES = ['', 'ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE', 'CUSTOMER', 'VENDOR'];
const today = () => new Date().toISOString().slice(0, 10);
const yearStart = () => `${new Date().getFullYear() - (new Date().getMonth() < 3 ? 1 : 0)}-04-01`;

export default function TallyView({ companyId, shell }: { companyId: number | ''; shell: Shell }) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [from, setFrom] = useState(yearStart());
  const [to, setTo] = useState(today());
  const base = `/api/v1/companies/${companyId}/tally`;

  const form = (f: File) => {
    const data = new FormData();
    data.append('file', f);
    const chosen = Object.fromEntries(Object.entries(overrides).filter(([, v]) => v));
    if (Object.keys(chosen).length) data.append('mapping', JSON.stringify(chosen));
    return data;
  };

  const runPreview = (f: File | null = file) => f && shell.run(async () => {
    setResult(null);
    setPreview(await apiUpload<Preview>(`${base}/import/preview`, form(f)));
  });

  const pick = (f: File | undefined) => {
    if (!f) return;
    setFile(f); setPreview(null); setResult(null); setOverrides({});
    runPreview(f);
  };

  const runImport = () => file && shell.run(async () => {
    if (!window.confirm('Create the accounts and parties listed and one draft journal per voucher? Journals still need approval before they reach the ledger.')) return;
    const res = await apiUpload<Result>(`${base}/import`, form(file));
    setResult(res);
    shell.notify(`Imported ${res.journalsCreated} draft journals, ${res.accountsCreated} accounts and ${res.partiesCreated} parties.`);
    setPreview(await apiUpload<Preview>(`${base}/import/preview`, form(file)));
  });

  const exportXml = () => shell.run(async () => {
    if (from > to) throw Error('The start date must be on or before the end date.');
    await apiDownload(`${base}/export?from=${from}&to=${to}`, `finora-vouchers-${from}-to-${to}.xml`);
  });

  if (companyId === '') return <p className="empty">Select or create a company first.</p>;
  const unmapped = preview?.ledgers.filter(l => l.action === 'UNMAPPED') ?? [];

  return <>
    <Panel title="Import from Tally" description="Upload a TallyPrime Day Book or vouchers export (XML). You can review exactly what will be created before anything is written. Imported vouchers become draft journals that need normal approval; opening balances are reported but never posted."
      aside={<span className="count">XML · up to 10 MB</span>}>
      <label className="secondary" style={{ cursor: 'pointer' }}>
        <FileUp size={15} /> Choose Tally XML file
        <input type="file" accept=".xml,text/xml,application/xml" hidden disabled={shell.busy}
          onChange={e => { pick(e.target.files?.[0]); e.target.value = ''; }} />
      </label>
      {file && <span className="muted"> {file.name}</span>}
    </Panel>

    {preview && <>
      <section className="stats">
        <Stat label="Vouchers" value={preview.summary.vouchers} note={`${preview.vouchers.from ?? '—'} to ${preview.vouchers.to ?? '—'}`} />
        <Stat label="Will import" value={preview.summary.importable} note={`${preview.summary.alreadyImported} already imported`} />
        <Stat label="Skipped / failed" value={`${preview.summary.skipped} / ${preview.summary.failed}`} note="See problems below" />
        <Stat label="New parties" value={preview.summary.partiesToCreate} note={`${preview.summary.ledgers} ledgers in file`} />
      </section>

      {preview.problemCount > 0 && <div className="alert error" role="alert">
        <strong>{preview.problemCount} problem{preview.problemCount === 1 ? '' : 's'} found</strong>
        <ul>{preview.problems.slice(0, 15).map((p, i) => <li key={i}>{p.message}</li>)}</ul>
        {preview.problemCount > 15 && <small>Showing the first 15.</small>}
      </div>}

      {unmapped.length > 0 && <Panel title="Ledgers that need a decision" description="Tally groups such as Suspense or custom top-level groups cannot be mapped automatically. Choose what each should become, then preview again.">
        <Table columns={3} isEmpty={false} empty="" head={<tr><th>Ledger</th><th>Tally group</th><th>Treat as</th></tr>}>
          {unmapped.map(l => <tr key={l.ledgerName}><td>{l.ledgerName}</td><td>{l.tallyGroup || '—'}</td>
            <td><select aria-label={`Treat ${l.ledgerName} as`} value={overrides[l.ledgerName] ?? ''} onChange={e => setOverrides({ ...overrides, [l.ledgerName]: e.target.value })}>
              {CHOICES.map(c => <option key={c} value={c}>{c || 'Not mapped'}</option>)}</select></td></tr>)}
        </Table>
        <button className="small" disabled={shell.busy} onClick={() => runPreview()}>Preview again</button>
      </Panel>}

      <Panel title="How ledgers will be mapped" aside={<span className="count">{preview.ledgers.length} ledgers</span>}>
        <Table columns={5} isEmpty={!preview.ledgers.length} empty="No ledgers in this file."
          head={<tr><th>Tally ledger</th><th>Tally group</th><th>Becomes</th><th className="right">Vouchers</th><th>Notes</th></tr>}>
          {preview.ledgers.map(l => <tr key={l.ledgerName}>
            <td>{l.ledgerName}</td><td><small>{l.primaryGroup || l.tallyGroup || '—'}</small></td>
            <td><span className={l.action === 'UNMAPPED' ? 'draft' : 'chip'}>{ACTION_LABEL[l.action] ?? l.action}</span>{' '}
              <small>{l.accountCode ? `${l.accountCode} ${l.accountName ?? ''}` : l.partyName ?? ''}</small></td>
            <td className="right">{l.voucherCount}</td>
            <td><small>{[l.reason, ...(l.warnings ?? [])].filter(Boolean).join(' · ') || '—'}</small></td>
          </tr>)}
        </Table>
      </Panel>

      <Panel title="Vouchers by type">
        <Table columns={5} isEmpty={!preview.vouchers.byType.length} empty="No vouchers."
          head={<tr><th>Type</th><th className="right">Total</th><th className="right">Will import</th><th className="right">Skipped</th><th className="right">Failed</th></tr>}>
          {preview.vouchers.byType.map(t => <tr key={t.voucherType}><td>{t.voucherType}</td><td className="right">{t.count}</td>
            <td className="right">{t.importable}</td><td className="right">{t.skipped}</td><td className="right">{t.failed}</td></tr>)}
        </Table>
        <p className="hint">{preview.openingBalances.note}</p>
        <button disabled={shell.busy || preview.summary.importable === 0} onClick={runImport}>Import {preview.summary.importable} vouchers as drafts</button>
      </Panel>
    </>}

    {result && <div className="alert success" role="status">
      <strong>Import finished.</strong> {result.journalsCreated} draft journals, {result.accountsCreated} accounts and {result.partiesCreated} parties created; {result.skipped} skipped, {result.failed} failed. {result.note}
    </div>}

    <Panel title="Export to Tally" description="Posted journals for a period as a Tally Import Data XML (ledger masters plus Journal vouchers). In Tally, use Import Data and choose Vouchers.">
      <form className="accountform" onSubmit={e => { e.preventDefault(); exportXml(); }}>
        <label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} required /></label>
        <label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} required /></label>
        <button disabled={shell.busy}><Download size={15} />Download XML</button>
      </form>
    </Panel>
  </>;
}
