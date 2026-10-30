let basicAuth = '';
let onUnauthorized: (() => void) | null = null;
export const setBasicAuth = (value: string) => { basicAuth = value; };
export const setUnauthorizedHandler = (fn: (() => void) | null) => { onUnauthorized = fn; };
export const money = (n: number | string) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(Number(n) || 0);
const TIMEOUT_MS = 30000;
export async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let r: Response;
  try {
    r = await fetch(url, { ...options, signal: controller.signal, headers: { 'Content-Type': 'application/json', ...(basicAuth ? { Authorization: `Basic ${basicAuth}` } : {}), ...(options?.headers || {}) } });
  } catch (e) {
    throw new Error(e instanceof DOMException && e.name === 'AbortError' ? 'The server took too long to respond. Please try again.' : 'Cannot reach the server. Check your connection and try again.');
  } finally { clearTimeout(timer); }
  const b = await r.json().catch(() => ({}));
  if (r.status === 401 && basicAuth) onUnauthorized?.();
  if (!r.ok) throw new Error(b.message || `Request failed (${r.status})`);
  return b as T;
}

// Multipart upload: the browser must set the Content-Type boundary itself, so no JSON header is sent.
export async function apiUpload<T>(url: string, form: FormData): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 90000);
  let r: Response;
  try {
    r = await fetch(url, { method: 'POST', body: form, signal: controller.signal, headers: basicAuth ? { Authorization: `Basic ${basicAuth}` } : {} });
  } catch (e) {
    throw new Error(e instanceof DOMException && e.name === 'AbortError' ? 'The server took too long to respond. Please try again.' : 'Cannot reach the server. Check your connection and try again.');
  } finally { clearTimeout(timer); }
  const b = await r.json().catch(() => ({}));
  if (r.status === 401 && basicAuth) onUnauthorized?.();
  if (!r.ok) throw new Error(b.message || `Upload failed (${r.status})`);
  return b as T;
}
