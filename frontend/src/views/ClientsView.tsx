import { useCallback, useEffect, useMemo, useState } from 'react';
import { CheckCheck, Pin, PinOff, Search } from 'lucide-react';
import { api, money } from '../api';
import { Panel, Table } from '../ui';
import type { Shell } from '../types';

type CloseStatus = 'CLEAN' | 'ATTENTION' | 'BLOCKED';
type Client = {
  companyId: number; name: string; gstin?: string | null; role: string; pinned: boolean; label?: string | null;
  closeStatus: CloseStatus; closeReasons: string[];
  metrics: {
    draftJournals: number; submittedJournals: number; approvedNotPosted: number; approvableByMe: number;
    unmatchedBankLines: number; overdueOpenPeriods: number; lastPostedJournalDate?: string | null;
    gstr2b?: { returnPeriod: string; openExceptions: number; itcAtRisk: number } | null;
    overdueReceivables: { count: number; amount: number };
  };
};
type Approval = {
  companyId: number; companyName: string; journalId: number; journalDate: string; description: string;
  totalDebit: number; submittedBy?: string | null; ageDays: number; periodOpen: boolean;
};
type BulkResult = { companyId: number; journalId: number; ok: boolean; error?: string | null };

const STATUS_CLASS: Record<CloseStatus, string> = { CLEAN: 'posted', ATTENTION: 'chip', BLOCKED: 'draft' };
const keyOf = (a: { companyId: number; journalId: number }) => `${a.companyId}:${a.journalId}`;

export default function ClientsView({ shell, onOpenCompany }: { shell: Shell; onOpenCompany: (companyId: number) => void }) {
  const [clients, setClients] = useState<Client[]>([]);
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [approvalTotal, setApprovalTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [failures, setFailures] = useState<BulkResult[]>([]);

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (search.trim()) params.set('search', search.trim());
    if (status) params.set('status', status);
    const [c, a] = await Promise.all([
      api<Client[]>(`/api/v1/workspace/clients?${params}`),
      api<{ items: Approval[]; total: number }>('/api/v1/workspace/approvals?limit=200'),
    ]);
    setClients(c); setApprovals(a.items); setApprovalTotal(a.total);
    // Drop selections that are no longer in the queue (approved elsewhere).
    setSelected(prev => new Set([...prev].filter(k => a.items.some(i => keyOf(i) === k))));
  }, [search, status]);

  useEffect(() => { const t = setTimeout(() => { load().catch(e => shell.notify(e instanceof Error ? e.message : 'Could not load clients.')); }, 250); return () => clearTimeout(t); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  const counts = useMemo(() => ({
    blocked: clients.filter(c => c.closeStatus === 'BLOCKED').length,
    attention: clients.filter(c => c.closeStatus === 'ATTENTION').length,
  }), [clients]);

  const togglePin = (c: Client) => shell.run(async () => {
    await api(`/api/v1/workspace/clients/${c.companyId}/settings`, { method: 'PUT', body: JSON.stringify({ pinned: !c.pinned, label: c.label ?? '' }) });
    await load();
  });

  const toggle = (a: Approval) => setSelected(prev => { const n = new Set(prev); const k = keyOf(a); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const allSelected = approvals.length > 0 && approvals.every(a => selected.has(keyOf(a)));

  const bulkApprove = () => shell.run(async () => {
    const items = approvals.filter(a => selected.has(keyOf(a))).map(a => ({ companyId: a.companyId, journalId: a.journalId }));
    if (!items.length) throw Error('Select at least one journal to approve.');
    if (!window.confirm(`Approve ${items.length} journal${items.length === 1 ? '' : 's'}? Approving does not post them; posting stays a separate step.`)) return;
    const res = await api<{ approved: number; failed: number; results: BulkResult[] }>('/api/v1/workspace/approvals/bulk', { method: 'POST', body: JSON.stringify({ items }) });
    setFailures(res.results.filter(r => !r.ok));
    setSelected(new Set());
    await load();
    shell.notify(`Approved ${res.approved} journal${res.approved === 1 ? '' : 's'}${res.failed ? `; ${res.failed} could not be approved (see below)` : ''}.`);
  });

  return <>
    <Panel title="Clients" aside={<span className="count">{clients.length} clients · {counts.blocked} blocked · {counts.attention} need attention</span>}
      description="Every company you are a member of, with what is holding up its close. Open a client to work in it.">
      <form className="accountform" onSubmit={e => e.preventDefault()}>
        <label>Search<span className="inline"><Search size={14} /><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Name, GSTIN or label" maxLength={100} /></span></label>
        <label>Status<select value={status} onChange={e => setStatus(e.target.value)}>
          <option value="">All</option><option value="BLOCKED">Blocked</option><option value="ATTENTION">Needs attention</option><option value="CLEAN">Clean</option></select></label>
      </form>
      <Table columns={8} isEmpty={!clients.length} empty="No clients match."
        head={<tr><th>Client</th><th>Status</th><th>Why</th><th className="right">Awaiting me</th><th className="right">Submitted</th><th>GSTR-2B</th><th className="right">Overdue</th><th>Action</th></tr>}>
        {clients.map(c => <tr key={c.companyId}>
          <td>{c.name}{c.label && <> <span className="chip">{c.label}</span></>}<br /><small className="muted mono">{c.gstin || 'No GSTIN'} · {c.role}</small></td>
          <td><span className={STATUS_CLASS[c.closeStatus]}>{c.closeStatus === 'ATTENTION' ? 'Attention' : c.closeStatus === 'BLOCKED' ? 'Blocked' : 'Clean'}</span></td>
          <td>{c.closeReasons.length ? <small>{c.closeReasons.join(' · ')}</small> : <span className="muted">Nothing outstanding</span>}</td>
          <td className="right">{c.metrics.approvableByMe}</td>
          <td className="right">{c.metrics.submittedJournals}</td>
          <td>{c.metrics.gstr2b ? <small>{c.metrics.gstr2b.returnPeriod}: {c.metrics.gstr2b.openExceptions} open · {money(c.metrics.gstr2b.itcAtRisk)} at risk</small> : <span className="muted">Not imported</span>}</td>
          <td className="right">{c.metrics.overdueReceivables.count ? `${c.metrics.overdueReceivables.count} · ${money(c.metrics.overdueReceivables.amount)}` : '—'}</td>
          <td><span className="inline">
            <button className="small" onClick={() => onOpenCompany(c.companyId)}>Open</button>
            <button className="small secondary" disabled={shell.busy} aria-label={c.pinned ? 'Unpin client' : 'Pin client'} onClick={() => togglePin(c)}>{c.pinned ? <PinOff size={13} /> : <Pin size={13} />}</button>
          </span></td>
        </tr>)}
      </Table>
    </Panel>

    <Panel title="Approval queue" aside={<span className="count">{approvalTotal} waiting{approvalTotal > approvals.length ? ` · showing ${approvals.length}` : ''}</span>}
      description="Submitted journals across your clients that you are allowed to approve. You can never approve your own submission unless that company has enabled it.">
      <div className="inline">
        <button disabled={shell.busy || !selected.size} onClick={bulkApprove}><CheckCheck size={15} />Approve selected ({selected.size})</button>
      </div>
      <Table columns={7} isEmpty={!approvals.length} empty="Nothing is waiting for your approval."
        head={<tr><th><input type="checkbox" aria-label="Select all" checked={allSelected}
          onChange={() => setSelected(allSelected ? new Set() : new Set(approvals.map(keyOf)))} /></th>
          <th>Client</th><th>Date</th><th>Description</th><th className="right">Amount</th><th>Submitted by</th><th className="right">Age</th></tr>}>
        {approvals.map(a => <tr key={keyOf(a)}>
          <td><input type="checkbox" aria-label={`Select journal ${a.journalId}`} checked={selected.has(keyOf(a))} onChange={() => toggle(a)} /></td>
          <td>{a.companyName}</td><td>{a.journalDate}</td>
          <td>{a.description}{!a.periodOpen && <> <span className="draft">Period closed</span></>}</td>
          <td className="right">{money(a.totalDebit)}</td><td>{a.submittedBy || '—'}</td><td className="right">{a.ageDays}d</td>
        </tr>)}
      </Table>
      {failures.length > 0 && <div className="alert error" role="alert">
        <strong>{failures.length} journal{failures.length === 1 ? '' : 's'} could not be approved:</strong>
        <ul>{failures.map(f => <li key={keyOf(f)}>Journal {f.journalId} (company {f.companyId}): {f.error}</li>)}</ul>
      </div>}
    </Panel>
  </>;
}
