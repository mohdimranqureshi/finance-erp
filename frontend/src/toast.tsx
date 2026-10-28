import { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, X } from 'lucide-react';

export type ToastKind = 'error' | 'success';
type Item = { id: number; kind: ToastKind; text: string };

const DISMISS_MS: Record<ToastKind, number> = { error: 9000, success: 5000 };
const MAX_VISIBLE = 4;

let items: Item[] = [];
let sequence = 0;
const listeners = new Set<(next: Item[]) => void>();
const emit = () => listeners.forEach(listener => listener(items));

export function dismissToast(id: number) {
  items = items.filter(item => item.id !== id);
  emit();
}

export function showToast(kind: ToastKind, text: string) {
  if (!text) return;
  const id = ++sequence;
  // Re-firing the same text replaces the old toast instead of stacking duplicates.
  items = [...items.filter(item => item.text !== text), { id, kind, text }].slice(-MAX_VISIBLE);
  emit();
  window.setTimeout(() => dismissToast(id), DISMISS_MS[kind]);
}

/** Mirrors a component's local error/message state into the global toast stack. */
export function useToastFeed(error?: string, message?: string) {
  useEffect(() => { if (error) showToast('error', error); }, [error]);
  useEffect(() => { if (message) showToast('success', message); }, [message]);
}

export function Toaster() {
  const [list, setList] = useState<Item[]>(items);
  useEffect(() => {
    listeners.add(setList);
    return () => { listeners.delete(setList); };
  }, []);

  if (!list.length) return null;
  return <div className="toaster" aria-live="polite">
    {list.map(item => <div key={item.id} className={`toast ${item.kind}`}
      role={item.kind === 'error' ? 'alert' : 'status'}>
      {item.kind === 'error' ? <AlertTriangle size={17} /> : <CheckCircle2 size={17} />}
      <p>{item.text}</p>
      <button type="button" className="ghost small" aria-label="Dismiss" onClick={() => dismissToast(item.id)}>
        <X size={14} />
      </button>
    </div>)}
  </div>;
}
