import { ArrowLeftRight, BarChart3, BookOpen, Bot, Briefcase, Building2, FileCheck2, FileText, KeyRound, LayoutDashboard, LogOut, ShieldCheck, Users } from 'lucide-react';
import type { View } from '../types';

const LINKS: { id: View; label: string; icon: typeof BookOpen; adminOnly?: boolean }[] = [
  { id: 'overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'clients', label: 'Clients', icon: Briefcase },
  { id: 'company', label: 'Company & accounts', icon: Building2 },
  { id: 'users', label: 'Users', icon: Users, adminOnly: true },
  { id: 'journals', label: 'Journals & ledger', icon: FileText },
  { id: 'reports', label: 'Reports', icon: BarChart3 },
  { id: 'gstr2b', label: 'GSTR-2B ITC', icon: FileCheck2 },
  { id: 'tally', label: 'Tally import/export', icon: ArrowLeftRight },
  { id: 'ai', label: 'AI assistant', icon: Bot },
  { id: 'security', label: 'Security', icon: KeyRound },
];

export default function Sidebar({ view, onNavigate, isAdmin, email, onSignOut }: {
  view: View; onNavigate: (v: View) => void; isAdmin: boolean; email: string; onSignOut: () => void;
}) {
  return <aside className="sidebar">
    <div className="brand">
      <div className="mark"><BookOpen size={19} /></div>
      <div><strong>FinoraAI</strong><small>FINANCE ERP</small></div>
    </div>

    <div className="navlabel">Workspace</div>
    {LINKS.filter(l => !l.adminOnly || isAdmin).map(({ id, label, icon: Icon }) =>
      <button key={id} className={`nav${view === id ? ' active' : ''}`} aria-current={view === id ? 'page' : undefined}
        onClick={() => onNavigate(id)}><Icon size={16} />{label}</button>)}

    <div className="sidebottom">
      <ShieldCheck size={15} />
      <span>{email}<br /><small>{isAdmin ? 'Administrator' : 'Signed in'}</small></span>
      <button className="nav signout" onClick={onSignOut}><LogOut size={17} />Sign out</button>
    </div>
  </aside>;
}
