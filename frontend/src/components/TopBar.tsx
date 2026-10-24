import { Moon, RefreshCw, Sun } from 'lucide-react';

export default function TopBar({ title, subtitle, theme, onToggleTheme, onRefresh, busy }: {
  title: string; subtitle: string; theme: 'light' | 'dark';
  onToggleTheme: () => void; onRefresh: () => void; busy: boolean;
}) {
  return <header className="topbar">
    <div>
      <div className="eyebrow">Finance workspace / {title}</div>
      <h1>{title}</h1>
      <p>{subtitle}</p>
    </div>
    <div className="topactions">
      <button className="secondary" aria-label="Toggle light or dark theme" onClick={onToggleTheme}>
        {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}{theme === 'dark' ? 'Light' : 'Dark'}
      </button>
      <button className="secondary" disabled={busy} onClick={onRefresh}><RefreshCw size={15} />Refresh</button>
    </div>
  </header>;
}
