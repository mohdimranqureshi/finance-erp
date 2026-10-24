import type { ReactNode } from 'react';

export function Panel({ title, description, aside, children }: {
  title: ReactNode; description?: ReactNode; aside?: ReactNode; children: ReactNode;
}) {
  return <section className="panel below">
    <div className="panelhead">
      <div><h2>{title}</h2>{description && <p>{description}</p>}</div>
      {aside}
    </div>
    {children}
  </section>;
}

export function Field({ label, children }: { label: ReactNode; children: ReactNode }) {
  return <label>{label}{children}</label>;
}

export function Alert({ kind = 'info', children }: { kind?: 'info' | 'error' | 'success'; children: ReactNode }) {
  return <div className={kind === 'info' ? 'alert' : `alert ${kind}`}>{children}</div>;
}

export function Stat({ label, value, note }: { label: string; value: ReactNode; note: ReactNode }) {
  return <div className="stat"><span>{label}</span><strong>{value}</strong><small>{note}</small></div>;
}

export function Table({ head, foot, children, empty, columns, isEmpty }: {
  head: ReactNode; foot?: ReactNode; children: ReactNode;
  empty: string; columns: number; isEmpty: boolean;
}) {
  return <div className="tablewrap"><table>
    <thead>{head}</thead>
    <tbody>{isEmpty ? <tr><td colSpan={columns} className="empty">{empty}</td></tr> : children}</tbody>
    {foot}
  </table></div>;
}
