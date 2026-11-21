import { useCallback, useEffect, useState } from 'react';
import { api, money } from '../api';
import { Panel, Table } from '../ui';
import type { Account, Shell } from '../types';

type Category = { code: string; name: string; usefulLifeYears?: number | null; basis?: string };
type Asset = {
  id: number; assetCode?: string | null; name: string; category: string; cost: number; putToUseDate: string; usefulLifeYears: number;
  method: string; accumulatedDepreciation: number; netBookValue: number; depreciatedTo?: string | null; status: string;
  disposalDate?: string | null; disposalGainLoss?: number | null; capitalisationJournalStatus?: string | null;
};
type Run = { id: number; periodEnd: string; totalAmount: number; assetCount: number; journalStatus: string };

const today = () => new Date().toISOString().slice(0, 10);
const monthEnd = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth() + 1, 0, 12).toISOString().slice(0, 10); };

export default function FixedAssetsView({ companyId, accounts, shell }: { companyId: number | ''; accounts: Account[]; shell: Shell }) {
  const base = `/api/v1/companies/${companyId}/fixed-assets`;
  const [categories, setCategories] = useState<Category[]>([]);
  const [note, setNote] = useState('');
  const [assets, setAssets] = useState<Asset[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [name, setName] = useState(''), [code, setCode] = useState(''), [category, setCategory] = useState(''), [cost, setCost] = useState('');
  const [puDate, setPuDate] = useState(today()), [life, setLife] = useState(''), [method, setMethod] = useState('SLM'), [residual, setResidual] = useState('');
  const [fromAcc, setFromAcc] = useState('');
  const [periodEnd, setPeriodEnd] = useState(monthEnd());

  const load = useCallback(async () => {
    if (companyId === '') return;
    const [a, r] = await Promise.all([api<Asset[]>(base), api<Run[]>(`${base}/depreciation-runs`)]);
    setAssets(a); setRuns(r);
  }, [base, companyId]);
  useEffect(() => {
    api<{ categories: Category[]; note: string }>('/api/v1/fixed-assets/categories').then(c => { setCategories(c.categories); setNote(c.note); }).catch(() => undefined);
  }, []);
  useEffect(() => { load().catch(e => shell.notify(e.message)); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  const chosen = categories.find(c => c.code === category);
  const expenses = accounts.filter(a => a.accountType === 'EXPENSE');

  const create = () => shell.run(async () => {
    await api(base, { method: 'POST', body: JSON.stringify({
      name: name.trim(), assetCode: code.trim() || null, category, cost: Number(cost), putToUseDate: puDate, method,
      usefulLifeYears: life ? Number(life) : null, residualValue: residual ? Number(residual) : null,
      capitaliseFromAccountId: fromAcc ? Number(fromAcc) : null }) });
    setName(''); setCode(''); setCost(''); setResidual(''); setFromAcc('');
    await load();
    shell.notify(fromAcc ? 'Asset added. A draft journal moves its cost from expense to fixed assets and needs approval.' : 'Asset added.');
  });

  const runDepreciation = () => shell.run(async () => {
    const r = await api<Run>(`${base}/depreciation-runs`, { method: 'POST', body: JSON.stringify({ periodEnd }) });
    await load();
    shell.notify(`Depreciation of ${money(r.totalAmount)} for ${r.assetCount} assets created as a draft journal for approval.`);
  });

  const dispose = (a: Asset) => shell.run(async () => {
    const date = window.prompt(`Disposal date for ${a.name} (YYYY-MM-DD)`, today());
    if (!date) return;
    const proceeds = window.prompt('Sale proceeds in rupees (0 if scrapped)', '0');
    if (proceeds === null) return;
    let proceedsAccountId: number | null = null;
    if (Number(proceeds) > 0) {
      const code = window.prompt('Account code that receives the proceeds (for example your bank account code)');
      const acc = accounts.find(x => x.code === code?.trim() && x.accountType === 'ASSET');
      if (!acc) throw Error('That is not an asset account code.');
      proceedsAccountId = acc.id;
    }
    await api(`${base}/${a.id}/dispose`, { method: 'POST', body: JSON.stringify({ disposalDate: date, proceeds: Number(proceeds), proceedsAccountId }) });
    await load();
    shell.notify('Disposal recorded as a draft journal with depreciation up to the disposal date and the gain or loss. GST on the sale is not handled here.');
  });

  if (companyId === '') return <p className="empty">Select or create a company first.</p>;

  return <>
    <Panel title="Add a fixed asset" description="Book depreciation follows Companies Act Schedule II style lives. The lives shown are reference data, so confirm them with your CA."
      aside={<span className="count">{assets.length} assets</span>}>
      <form className="accountform" onSubmit={e => { e.preventDefault(); create(); }}>
        <label>Name<input value={name} onChange={e => setName(e.target.value)} maxLength={200} required /></label>
        <label>Asset code (optional)<input value={code} onChange={e => setCode(e.target.value)} maxLength={30} pattern="[A-Za-z0-9/_.\-]*" /></label>
        <label>Category<select value={category} onChange={e => { setCategory(e.target.value); setLife(''); }} required>
          <option value="">Select</option>{categories.map(c => <option key={c.code} value={c.code}>{c.name}{c.usefulLifeYears ? ` (${c.usefulLifeYears} yrs)` : ''}</option>)}</select></label>
        <label>Cost<input type="number" min="0.01" step="0.01" value={cost} onChange={e => setCost(e.target.value)} required /></label>
        <label>Put to use on<input type="date" value={puDate} onChange={e => setPuDate(e.target.value)} required /></label>
        <label>Useful life (years){chosen?.usefulLifeYears ? '' : ' *'}<input type="number" min="1" max="100" value={life} placeholder={chosen?.usefulLifeYears ? String(chosen.usefulLifeYears) : ''} onChange={e => setLife(e.target.value)} required={!chosen?.usefulLifeYears} /></label>
        <label>Method<select value={method} onChange={e => setMethod(e.target.value)}><option value="SLM">Straight line</option><option value="WDV">Written down value</option></select></label>
        <label>Residual value (default 5%)<input type="number" min="0" step="0.01" value={residual} onChange={e => setResidual(e.target.value)} /></label>
        <label title="Pick the expense account the purchase bill used, to move its cost into fixed assets with a draft journal.">Capitalise from expense<select value={fromAcc} onChange={e => setFromAcc(e.target.value)}>
          <option value="">Already capitalised</option>{expenses.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <button disabled={shell.busy}>Add asset</button>
      </form>
      {note && <p className="hint">{note}</p>}
    </Panel>

    <Panel title="Fixed asset register" aside={<span className="count">{assets.length}</span>}>
      <Table columns={8} isEmpty={!assets.length} empty="No fixed assets yet."
        head={<tr><th>Asset</th><th>Category</th><th>In use from</th><th className="right">Cost</th><th className="right">Accumulated</th><th className="right">Net book value</th><th>Status</th><th>Action</th></tr>}>
        {assets.map(a => <tr key={a.id}>
          <td>{a.name}{a.assetCode && <><br /><small className="mono muted">{a.assetCode}</small></>}</td>
          <td><small>{a.category} · {a.method} · {a.usefulLifeYears}y</small></td><td>{a.putToUseDate}</td>
          <td className="right">{money(a.cost)}</td><td className="right">{money(a.accumulatedDepreciation)}</td><td className="right">{money(a.netBookValue)}</td>
          <td><span className={a.status === 'DISPOSED' ? 'draft' : 'chip'}>{a.status.replace('_', ' ').toLowerCase()}</span>
            {a.disposalGainLoss != null && <><br /><small>{Number(a.disposalGainLoss) >= 0 ? 'Gain' : 'Loss'} {money(Math.abs(Number(a.disposalGainLoss)))}</small></>}</td>
          <td>{a.status !== 'DISPOSED' && <button className="small secondary" disabled={shell.busy} onClick={() => dispose(a)}>Dispose</button>}</td>
        </tr>)}
      </Table>
    </Panel>

    <Panel title="Depreciation" description="One draft journal for all active assets up to the date you choose, pro-rata by days and never below residual value. Runs cannot overlap, so charge it after the period ends."
      aside={<span className="count">{runs.length} runs</span>}>
      <form className="accountform" onSubmit={e => { e.preventDefault(); runDepreciation(); }}>
        <label>Depreciate up to<input type="date" value={periodEnd} onChange={e => setPeriodEnd(e.target.value)} required /></label>
        <button disabled={shell.busy || !assets.length}>Create depreciation journal</button>
      </form>
      <Table columns={4} isEmpty={!runs.length} empty="No depreciation runs yet."
        head={<tr><th>Up to</th><th className="right">Assets</th><th className="right">Amount</th><th>Journal</th></tr>}>
        {runs.map(r => <tr key={r.id}><td>{r.periodEnd}</td><td className="right">{r.assetCount}</td><td className="right">{money(r.totalAmount)}</td>
          <td><span className={r.journalStatus === 'POSTED' ? 'posted' : 'draft'}>{r.journalStatus}</span></td></tr>)}
      </Table>
    </Panel>
  </>;
}
