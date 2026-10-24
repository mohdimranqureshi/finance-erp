import { money } from '../api';
import { Stat } from '../ui';
import IndiaModule from '../IndiaModule';
import IndiaOps from '../IndiaOps';
import type { Account, Journal } from '../types';

export default function OverviewView({ companyId, accounts, journals, debits, credits, reload }: {
  companyId: number | ''; accounts: Account[]; journals: Journal[];
  debits: number; credits: number; reload: () => Promise<void>;
}) {
  const posted = journals.filter(j => j.status === 'POSTED').length;
  return <>
    <section className="stats">
      <Stat label="Chart of accounts" value={accounts.length} note="Configured accounts" />
      <Stat label="Journal entries" value={journals.length} note={`${posted} posted`} />
      <Stat label="Debits posted" value={money(debits)} note="Calendar year to date" />
      <Stat label="Credits posted" value={money(credits)} note="Calendar year to date" />
    </section>

    {companyId !== '' && <>
      <IndiaModule companyId={Number(companyId)} accounts={accounts} onLedgerChanged={reload} />
      <IndiaOps companyId={Number(companyId)} accounts={accounts} onLedgerChanged={reload} />
    </>}
  </>;
}
