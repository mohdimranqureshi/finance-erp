import { useState } from 'react';
import { Plus } from 'lucide-react';
import type { Company } from '../types';

export default function CompanyStrip({ companies, companyId, onSelect, onCreate, busy }: {
  companies: Company[]; companyId: number | ''; onSelect: (id: number | '') => void;
  onCreate: (name: string) => Promise<boolean>; busy: boolean;
}) {
  const [name, setName] = useState('');
  return <section className="companystrip">
    <div>
      <label htmlFor="active-company">Active company</label>
      <select id="active-company" value={companyId} onChange={e => onSelect(e.target.value ? Number(e.target.value) : '')}>
        <option value="">Select company</option>
        {companies.map(c => <option key={c.id} value={c.id}>{c.name} · {c.baseCurrency}</option>)}
      </select>
    </div>
    <div className="inline">
      <input value={name} onChange={e => setName(e.target.value)} placeholder="New company name" aria-label="New company name" />
      <button disabled={busy} onClick={async () => { if (await onCreate(name)) setName(''); }}>
        <Plus size={15} />Create company
      </button>
    </div>
  </section>;
}
