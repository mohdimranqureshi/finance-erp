let basicAuth = '';
export const setBasicAuth = (value: string) => { basicAuth = value; };
export const money = (n: number | string) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(Number(n) || 0);
export async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const r = await fetch(url, { ...options, headers: { 'Content-Type': 'application/json', ...(basicAuth ? { Authorization: `Basic ${basicAuth}` } : {}), ...(options?.headers || {}) } });
  const b = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(b.message || `Request failed (${r.status})`);
  return b as T;
}
