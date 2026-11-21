// Browser auth uses the server session cookie (HttpOnly) plus a CSRF token: no credentials are stored in JS.
let onUnauthorized: (() => void) | null = null;
export const setUnauthorizedHandler = (fn: (() => void) | null) => { onUnauthorized = fn; };
export const money = (n: number | string) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(Number(n) || 0);
const TIMEOUT_MS = 30000;
const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS'];
const AUTH_PATHS = ['/api/v1/auth/login', '/api/v1/auth/logout', '/api/v1/auth/csrf'];

export type SessionUser = { email: string; admin: boolean; mfaEnabled: boolean };
/** The password was accepted but a TOTP or recovery code is still needed. */
export class MfaRequiredError extends Error {}

function readCookie(name: string): string {
  const entry = document.cookie.split(';').map(c => c.trim()).find(c => c.startsWith(`${name}=`));
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : '';
}

/** The server issues the XSRF-TOKEN cookie on demand; it must exist before any POST/PUT/DELETE. */
export async function ensureCsrfToken(): Promise<void> {
  if (readCookie('XSRF-TOKEN')) return;
  await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' }).catch(() => undefined);
}

async function send(url: string, init: RequestInit, timeoutMs: number): Promise<{ r: Response; b: any }> {
  const method = (init.method || 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (!SAFE_METHODS.includes(method)) {
    await ensureCsrfToken();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let r: Response;
  try {
    r = await fetch(url, { ...init, headers, credentials: 'same-origin', signal: controller.signal });
  } catch (e) {
    throw new Error(e instanceof DOMException && e.name === 'AbortError' ? 'The server took too long to respond. Please try again.' : 'Cannot reach the server. Check your connection and try again.');
  } finally { clearTimeout(timer); }
  const b = await r.json().catch(() => ({}));
  if (r.status === 401 && !AUTH_PATHS.some(p => url.startsWith(p))) onUnauthorized?.();
  return { r, b };
}

export async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const { r, b } = await send(url, { ...options, headers: { 'Content-Type': 'application/json', ...(options?.headers || {}) } }, TIMEOUT_MS);
  if (!r.ok) throw new Error(b.message || `Request failed (${r.status})`);
  return b as T;
}

// Multipart upload: the browser must set the Content-Type boundary itself, so no JSON header is sent.
export async function apiUpload<T>(url: string, form: FormData): Promise<T> {
  const { r, b } = await send(url, { method: 'POST', body: form }, 90000);
  if (!r.ok) throw new Error(b.message || `Upload failed (${r.status})`);
  return b as T;
}

/** Downloads a file (e.g. the Tally XML export) with the session cookie; GET requests need no CSRF token. */
export async function apiDownload(url: string, filename: string): Promise<void> {
  let r: Response;
  try { r = await fetch(url, { credentials: 'same-origin' }); }
  catch { throw new Error('Cannot reach the server. Check your connection and try again.'); }
  if (r.status === 401) onUnauthorized?.();
  if (!r.ok) { const b = await r.json().catch(() => ({})); throw new Error(b.message || `Download failed (${r.status})`); }
  const href = URL.createObjectURL(await r.blob());
  const link = document.createElement('a');
  link.href = href; link.download = filename; link.click();
  URL.revokeObjectURL(href);
}

export async function login(email: string, password: string, second?: { totpCode?: string; recoveryCode?: string }): Promise<SessionUser> {
  const { r, b } = await send('/api/v1/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, ...(second || {}) }),
  }, TIMEOUT_MS);
  if (r.status === 401 && b.mfaRequired) throw new MfaRequiredError(b.message || 'Enter your verification code.');
  if (!r.ok) throw new Error(b.message || `Sign in failed (${r.status})`);
  return b as SessionUser;
}

/** Ends the server session; local state is cleared by the caller even if the server cannot be reached. */
export async function logout(): Promise<void> {
  await send('/api/v1/auth/logout', { method: 'POST' }, TIMEOUT_MS).catch(() => undefined);
}
