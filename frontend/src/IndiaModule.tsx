import { useEffect, useState } from 'react';
import { FileText, Landmark, Plus, Receipt, Sparkles, Users } from 'lucide-react';
import { api, money } from './api';
import { useToastFeed } from './toast';
import InvoiceUpload from './InvoiceUpload';

type Account = { id: number; code: string; name: string; accountType: string };
type State = { code: string; name: string };
type Reference = { states: State[]; gstRates: number[]; tdsSections: { section: string; description: string; indicativeRate: number }[] };
type Party = { id: number; partyType: string; name: string; gstin?: string | null; stateCode: string };
type Doc = { id: number; invoiceNumber?: string; vendorInvoiceNumber?: string; invoiceDate?: string; billDate?: string; customerName?: string; vendorName?: string; supplyType: string; taxableAmount: number; cgstAmount: number; sgstAmount: number; igstAmount: number; totalAmount: number; tdsAmount?: number; status: string; journalEntryId?: number | null; journalStatus?: string | null };
type Heads = { taxableValue: number; cgst: number; sgst: number; igst: number };
type GstSummary = { outwardSupplies: Heads; eligibleItc: Heads; netByHead: { cgst: number; sgst: number; igst: number; total: number }; note: string };
type Row = { accountId: number; code: string; name: string; amount: number };
type Pnl = { income: Row[]; expenses: Row[]; totalIncome: number; totalExpenses: number; netProfit: number };
type Sheet = { assets: Row[]; liabilities: Row[]; equity: Row[]; accumulatedProfit: number; totalAssets: number; totalLiabilities: number; totalEquity: number; balanced: boolean };
type Suggestion = { matched: boolean; reason: string; expenseAccount?: { id: number; code: string; name: string } | null; tdsSection?: string | null; indicativeTdsRate?: number | null; caveat?: string };

const today = () => new Date().toISOString().slice(0, 10);
const fyStart = () => { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-04-01`; };

export default function IndiaModule({ companyId, accounts, onLedgerChanged }: { companyId: number; accounts: Account[]; onLedgerChanged: () => Promise<void> }) {
  const base = `/api/v1/companies/${companyId}`;
  const [ref, setRef] = useState<Reference | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const [gstin, setGstin] = useState(''), [pan, setPan] = useState(''), [stateCode, setStateCode] = useState(''), [addr, setAddr] = useState(''), [city, setCity] = useState(''), [pin, setPin] = useState('');
  const [parties, setParties] = useState<Party[]>([]), [invoices, setInvoices] = useState<Doc[]>([]), [bills, setBills] = useState<Doc[]>([]);
  const [pType, setPType] = useState('CUSTOMER'), [pName, setPName] = useState(''), [pGstin, setPGstin] = useState(''), [pState, setPState] = useState(''), [pAddr, setPAddr] = useState(''), [pCity, setPCity] = useState(''), [pPin, setPPin] = useState('');
  const [invCustomer, setInvCustomer] = useState(''), [invDate, setInvDate] = useState(today()), [invDesc, setInvDesc] = useState(''), [invHsn, setInvHsn] = useState('998314'), [invQty, setInvQty] = useState('1'), [invPrice, setInvPrice] = useState(''), [invRate, setInvRate] = useState('18');
  const [billVendor, setBillVendor] = useState(''), [billNo, setBillNo] = useState(''), [billDate, setBillDate] = useState(today()), [billDesc, setBillDesc] = useState(''), [billHsn, setBillHsn] = useState('998311'), [billPrice, setBillPrice] = useState(''), [billRate, setBillRate] = useState('18'), [billAccount, setBillAccount] = useState(''), [billItc, setBillItc] = useState(true), [tdsSection, setTdsSection] = useState(''), [tdsRate, setTdsRate] = useState('');
  const [suggestion, setSuggestion] = useState<Suggestion | null>(null);
  const [gst, setGst] = useState<GstSummary | null>(null), [pnl, setPnl] = useState<Pnl | null>(null), [sheet, setSheet] = useState<Sheet | null>(null);

  async function load() {
    const [p, i, b, prof] = await Promise.all([
      api<Party[]>(`${base}/parties`), api<Doc[]>(`${base}/sales-invoices`), api<Doc[]>(`${base}/purchase-bills`),
      api<{ gstin?: string; pan?: string; stateCode?: string; addressLine?: string; city?: string; pincode?: string }>(`${base}/india/profile`)]);
    setParties(p); setInvoices(i); setBills(b);
    setGstin(prof.gstin || ''); setPan(prof.pan || ''); setStateCode(prof.stateCode || '');
    setAddr(prof.addressLine || ''); setCity(prof.city || ''); setPin(prof.pincode || '');
  }
  useEffect(() => {
    api<Reference>('/api/v1/india/reference').then(setRef).catch(() => undefined);
    load().catch(e => setError(e.message));
  }, [companyId]);

  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : 'Unexpected error'); } finally { setBusy(false); }
  }
  const post = (url: string, body?: unknown) => api<Doc>(url, { method: 'POST', body: JSON.stringify(body ?? {}) });

  const saveProfile = () => run(async () => {
    await api(`${base}/india/profile`, { method: 'PUT', body: JSON.stringify({ gstin, pan, stateCode, addressLine: addr, city, pincode: pin }) });
    setMessage('India GST profile saved.');
  });
  const seedChart = () => run(async () => {
    const r = await api<{ created: number }>(`${base}/india/chart-of-accounts`, { method: 'POST' });
    await onLedgerChanged(); setMessage(`Indian chart of accounts ready (${r.created} new accounts).`);
  });
  const addParty = () => run(async () => {
    await api(`${base}/parties`, { method: 'POST', body: JSON.stringify({ partyType: pType, name: pName, gstin: pGstin, stateCode: pState, addressLine: pAddr, city: pCity, pincode: pPin }) });
    setPName(''); setPGstin(''); setPAddr(''); setPCity(''); setPPin(''); await load(); setMessage('Party added.');
  });
  const createInvoice = () => run(async () => {
    await post(`${base}/sales-invoices`, { customerId: Number(invCustomer), invoiceDate: invDate, lines: [{ description: invDesc, hsnSac: invHsn, quantity: Number(invQty), unitPrice: Number(invPrice), gstRate: Number(invRate) }] });
    setInvDesc(''); setInvPrice(''); await load(); setMessage('Draft invoice created with GST computed by place of supply.');
  });
  const issueInvoice = (id: number) => run(async () => {
    const d = await post(`${base}/sales-invoices/${id}/issue`);
    await load(); await onLedgerChanged(); setMessage(`Issued ${d.invoiceNumber}. A draft journal #${d.journalEntryId} now needs independent approval.`);
  });
  const createBill = () => run(async () => {
    await post(`${base}/purchase-bills`, {
      vendorId: Number(billVendor), vendorInvoiceNumber: billNo, billDate, itcEligible: billItc,
      tdsSection: tdsSection || null, tdsRate: tdsSection ? Number(tdsRate) : null,
      lines: [{ description: billDesc, hsnSac: billHsn, quantity: 1, unitPrice: Number(billPrice), gstRate: Number(billRate), expenseAccountId: billAccount ? Number(billAccount) : null }]
    });
    setBillNo(''); setBillDesc(''); setBillPrice(''); await load(); setMessage('Draft purchase bill created.');
  });
  const bookBill = (id: number) => run(async () => {
    const d = await post(`${base}/purchase-bills/${id}/book`);
    await load(); await onLedgerChanged(); setMessage(`Bill booked. Draft journal #${d.journalEntryId} now needs independent approval.`);
  });
  const afterUpload = async (msg: string) => { await load(); setMessage(msg); };
  const suggest = () => run(async () => {
    const vendor = parties.find(p => String(p.id) === billVendor);
    const s = await api<Suggestion>(`${base}/ai/suggest-bill-coding`, { method: 'POST', body: JSON.stringify({ description: billDesc, vendorName: vendor?.name }) });
    setSuggestion(s);
    if (s.matched && s.expenseAccount) setBillAccount(String(s.expenseAccount.id));
    if (s.matched && s.tdsSection) { setTdsSection(s.tdsSection); setTdsRate(String(s.indicativeTdsRate ?? '')); }
  });
  const loadReports = () => run(async () => {
    const q = `from=${fyStart()}&to=${today()}`;
    const [g, p, s] = await Promise.all([api<GstSummary>(`${base}/reports/gst-summary?${q}`), api<Pnl>(`${base}/reports/profit-and-loss?${q}`), api<Sheet>(`${base}/reports/balance-sheet?asOf=${today()}`)]);
    setGst(g); setPnl(p); setSheet(s);
  });

  const customers = parties.filter(p => p.partyType === 'CUSTOMER'), vendors = parties.filter(p => p.partyType === 'VENDOR');
  const expenseAccounts = accounts.filter(a => a.accountType === 'EXPENSE');
  const stateName = (c: string) => ref?.states.find(s => s.code === c)?.name || c;
  const stateOptions = <>{ref?.states.map(s => <option key={s.code} value={s.code}>{s.code} - {s.name}</option>)}</>;
  const docRow = (d: Doc, kind: 'sale' | 'bill') => (
    <tr key={d.id}>
      <td className="mono">{kind === 'sale' ? d.invoiceNumber || `Draft #${d.id}` : d.vendorInvoiceNumber}</td>
      <td>{kind === 'sale' ? d.invoiceDate : d.billDate}</td><td>{kind === 'sale' ? d.customerName : d.vendorName}</td>
      <td><span className="chip">{d.supplyType === 'INTER_STATE' ? 'IGST' : 'CGST+SGST'}</span></td>
      <td className="right">{money(d.taxableAmount)}</td><td className="right">{money(Number(d.cgstAmount) + Number(d.sgstAmount) + Number(d.igstAmount))}</td>
      {kind === 'bill' && <td className="right">{money(d.tdsAmount || 0)}</td>}
      <td className="right">{money(d.totalAmount)}</td>
      <td>{d.status === 'DRAFT'
        ? <button className="small" disabled={busy} onClick={() => kind === 'sale' ? issueInvoice(d.id) : bookBill(d.id)}>{kind === 'sale' ? 'Issue' : 'Book'}</button>
        : <span className="muted">Journal #{d.journalEntryId} {d.journalStatus}</span>}</td>
    </tr>);

  useToastFeed(error, message);
  return <>
    <div className="eyebrow" style={{ marginTop: 24 }}>INDIA · GST · TDS</div>    <section className="panel below">
      <div className="panelhead"><div><h2>India GST profile</h2><p>GSTIN is validated with its checksum; the state drives CGST/SGST vs IGST</p></div><span className="count">GST</span></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); saveProfile(); }}>
        <label>GSTIN (blank if unregistered)<input value={gstin} onChange={e => setGstin(e.target.value.toUpperCase())} maxLength={15} placeholder="27AAPFU0939F1ZV" /></label>
        <label>PAN<input value={pan} onChange={e => setPan(e.target.value.toUpperCase())} maxLength={10} /></label>
        <label>State<select value={stateCode} onChange={e => setStateCode(e.target.value)} required><option value="">Select state</option>{stateOptions}</select></label>
        <label>Address<input value={addr} onChange={e => setAddr(e.target.value)} maxLength={200} /></label>
        <label>City<input value={city} onChange={e => setCity(e.target.value)} maxLength={100} /></label>
        <label>PIN code<input value={pin} onChange={e => setPin(e.target.value)} pattern="[1-9][0-9]{5}" maxLength={6} /></label>
        <button disabled={busy}>Save profile</button>
        <button type="button" className="secondary wide" disabled={busy} onClick={seedChart}><Landmark size={15} />Set up Indian chart of accounts</button>
      </form>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><Users size={16} /> Customers and vendors</h2><p>{customers.length} customers · {vendors.length} vendors</p></div></div>
      <form className="accountform" onSubmit={e => { e.preventDefault(); addParty(); }}>
        <label>Type<select value={pType} onChange={e => setPType(e.target.value)}><option>CUSTOMER</option><option>VENDOR</option></select></label>
        <label>Name<input value={pName} onChange={e => setPName(e.target.value)} required /></label>
        <label>GSTIN<input value={pGstin} onChange={e => setPGstin(e.target.value.toUpperCase())} maxLength={15} /></label>
        <label>State<select value={pState} onChange={e => setPState(e.target.value)} required><option value="">Select state</option>{stateOptions}</select></label>
        <label>Address<input value={pAddr} onChange={e => setPAddr(e.target.value)} maxLength={200} /></label>
        <label>City<input value={pCity} onChange={e => setPCity(e.target.value)} maxLength={100} /></label>
        <label>PIN code<input value={pPin} onChange={e => setPPin(e.target.value)} pattern="[1-9][0-9]{5}" maxLength={6} /></label>
        <button disabled={busy}><Plus size={15} />Add party</button>
      </form>
      <div className="tablewrap"><table><thead><tr><th>Name</th><th>Type</th><th>GSTIN</th><th>State</th></tr></thead><tbody>
        {parties.map(p => <tr key={p.id}><td>{p.name}</td><td><span className="chip">{p.partyType}</span></td><td className="mono">{p.gstin || 'Unregistered'}</td><td>{stateName(p.stateCode)}</td></tr>)}
        {!parties.length && <tr><td colSpan={4} className="empty">Add a customer or vendor above.</td></tr>}</tbody></table></div>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><FileText size={16} /> GST sales invoices</h2><p>Issuing assigns a gapless number and creates a draft journal for maker-checker approval</p></div><span className="count">{invoices.length}</span></div>
      <InvoiceUpload companyId={companyId} kind="sale" parties={customers} busy={busy} run={run} onCreated={afterUpload} />
      <form className="accountform" onSubmit={e => { e.preventDefault(); createInvoice(); }}>
        <label>Customer<select value={invCustomer} onChange={e => setInvCustomer(e.target.value)} required><option value="">Select</option>{customers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        <label>Date<input type="date" value={invDate} onChange={e => setInvDate(e.target.value)} required /></label>
        <label>Description<input value={invDesc} onChange={e => setInvDesc(e.target.value)} required /></label>
        <label>HSN/SAC<input value={invHsn} onChange={e => setInvHsn(e.target.value)} pattern="\d{4}|\d{6}|\d{8}" required /></label>
        <label>Qty<input type="number" min="0.0001" step="any" value={invQty} onChange={e => setInvQty(e.target.value)} required /></label>
        <label>Unit price<input type="number" min="0.01" step="any" value={invPrice} onChange={e => setInvPrice(e.target.value)} required /></label>
        <label>GST %<select value={invRate} onChange={e => setInvRate(e.target.value)}>{(ref?.gstRates || [0, 5, 18]).map(r => <option key={r}>{r}</option>)}</select></label>
        <button disabled={busy || !customers.length}><Plus size={15} />Create draft invoice</button>
      </form>
      <div className="tablewrap"><table><thead><tr><th>Invoice</th><th>Date</th><th>Customer</th><th>Tax</th><th className="right">Taxable</th><th className="right">GST</th><th className="right">Total</th><th>Action</th></tr></thead><tbody>
        {invoices.map(d => docRow(d, 'sale'))}{!invoices.length && <tr><td colSpan={8} className="empty">No invoices yet.</td></tr>}</tbody></table></div>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2><Receipt size={16} /> Purchase bills (ITC and TDS)</h2><p>Duplicate vendor invoice numbers are rejected</p></div><span className="count">{bills.length}</span></div>
      <InvoiceUpload companyId={companyId} kind="bill" parties={vendors} busy={busy} run={run} onCreated={afterUpload} />
      <form className="accountform" onSubmit={e => { e.preventDefault(); createBill(); }}>
        <label>Vendor<select value={billVendor} onChange={e => setBillVendor(e.target.value)} required><option value="">Select</option>{vendors.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        <label>Vendor invoice no.<input value={billNo} onChange={e => setBillNo(e.target.value)} required /></label>
        <label>Date<input type="date" value={billDate} onChange={e => setBillDate(e.target.value)} required /></label>
        <label>Description<input value={billDesc} onChange={e => setBillDesc(e.target.value)} required /></label>
        <label>HSN/SAC<input value={billHsn} onChange={e => setBillHsn(e.target.value)} pattern="\d{4}|\d{6}|\d{8}" required /></label>
        <label>Amount before GST<input type="number" min="0.01" step="any" value={billPrice} onChange={e => setBillPrice(e.target.value)} required /></label>
        <label>GST %<select value={billRate} onChange={e => setBillRate(e.target.value)}>{(ref?.gstRates || [0, 5, 18]).map(r => <option key={r}>{r}</option>)}</select></label>
        <label>Expense account<select value={billAccount} onChange={e => setBillAccount(e.target.value)}><option value="">Default purchases</option>{expenseAccounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <label>TDS section<select value={tdsSection} onChange={e => { setTdsSection(e.target.value); setTdsRate(String(ref?.tdsSections.find(t => t.section === e.target.value)?.indicativeRate ?? '')); }}><option value="">None</option>{ref?.tdsSections.map(t => <option key={t.section} value={t.section}>{t.section} — {t.description}</option>)}</select></label>
        <label>TDS % (verify)<input type="number" min="0.01" max="30" step="0.01" value={tdsRate} onChange={e => setTdsRate(e.target.value)} disabled={!tdsSection} /></label>
        <label><span>Claim ITC</span><input type="checkbox" checked={billItc} onChange={e => setBillItc(e.target.checked)} /></label>
        <button disabled={busy || !vendors.length}><Plus size={15} />Create draft bill</button>
        <button type="button" className="secondary" disabled={busy || !billDesc} onClick={suggest}><Sparkles size={15} />Suggest coding</button>
      </form>
      {suggestion && <p className="hint">{suggestion.reason}{suggestion.matched && suggestion.caveat ? ` ${suggestion.caveat}` : ''} Review before saving.</p>}
      <div className="tablewrap"><table><thead><tr><th>Vendor invoice</th><th>Date</th><th>Vendor</th><th>Tax</th><th className="right">Taxable</th><th className="right">GST</th><th className="right">TDS</th><th className="right">Total</th><th>Action</th></tr></thead><tbody>
        {bills.map(d => docRow(d, 'bill'))}{!bills.length && <tr><td colSpan={9} className="empty">No bills yet.</td></tr>}</tbody></table></div>
    </section>

    <section className="panel below">
      <div className="panelhead"><div><h2>GST summary and financial statements</h2><p>Posted journals only · current financial year to date</p></div><button disabled={busy} onClick={loadReports}>Load reports</button></div>
      {gst && <div className="tablewrap"><table><thead><tr><th>GST (indicative)</th><th className="right">Taxable value</th><th className="right">CGST</th><th className="right">SGST</th><th className="right">IGST</th></tr></thead><tbody>
        <tr><td>Outward supplies</td><td className="right">{money(gst.outwardSupplies.taxableValue)}</td><td className="right">{money(gst.outwardSupplies.cgst)}</td><td className="right">{money(gst.outwardSupplies.sgst)}</td><td className="right">{money(gst.outwardSupplies.igst)}</td></tr>
        <tr><td>Eligible ITC</td><td className="right">{money(gst.eligibleItc.taxableValue)}</td><td className="right">{money(gst.eligibleItc.cgst)}</td><td className="right">{money(gst.eligibleItc.sgst)}</td><td className="right">{money(gst.eligibleItc.igst)}</td></tr>
        <tr><td><strong>Net by head</strong></td><td /><td className="right">{money(gst.netByHead.cgst)}</td><td className="right">{money(gst.netByHead.sgst)}</td><td className="right">{money(gst.netByHead.igst)}</td></tr></tbody></table><p className="hint">{gst.note}</p></div>}
      {pnl && <div className="tablewrap"><table><thead><tr><th colSpan={2}>Profit and loss</th><th className="right">Amount</th></tr></thead><tbody>
        {pnl.income.map(r => <tr key={r.accountId}><td className="mono">{r.code}</td><td>{r.name}</td><td className="right">{money(r.amount)}</td></tr>)}
        {pnl.expenses.map(r => <tr key={r.accountId}><td className="mono">{r.code}</td><td>{r.name}</td><td className="right">({money(r.amount)})</td></tr>)}</tbody>
        <tfoot><tr><th colSpan={2}>Net profit</th><th className="right">{money(pnl.netProfit)}</th></tr></tfoot></table></div>}
      {sheet && <div className="tablewrap"><table><thead><tr><th colSpan={2}>Balance sheet</th><th className="right">Amount</th></tr></thead><tbody>
        {sheet.assets.map(r => <tr key={r.accountId}><td className="mono">{r.code}</td><td>{r.name}</td><td className="right">{money(r.amount)}</td></tr>)}
        <tr><th colSpan={2}>Total assets</th><th className="right">{money(sheet.totalAssets)}</th></tr>
        {sheet.liabilities.map(r => <tr key={r.accountId}><td className="mono">{r.code}</td><td>{r.name}</td><td className="right">{money(r.amount)}</td></tr>)}
        {sheet.equity.map(r => <tr key={r.accountId}><td className="mono">{r.code}</td><td>{r.name}</td><td className="right">{money(r.amount)}</td></tr>)}
        <tr><td /><td>Accumulated profit (no year-end close yet)</td><td className="right">{money(sheet.accumulatedProfit)}</td></tr></tbody>
        <tfoot><tr><th colSpan={2}>Liabilities and equity {sheet.balanced ? '' : '(OUT OF BALANCE)'}</th><th className="right">{money(Number(sheet.totalLiabilities) + Number(sheet.totalEquity))}</th></tr></tfoot></table></div>}
    </section>
  </>;
}
