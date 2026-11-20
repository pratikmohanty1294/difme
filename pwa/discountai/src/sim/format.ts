// Money is in Indian rupees, with Indian digit grouping: ₹8,800 · ₹1,00,000.
export const inr = (n: number, digits = 0) =>
  `₹${n.toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
export const inr2 = (n: number) => inr(n, 2);
export const pct = (n: number, d = 1) => `${n.toFixed(d)}%`;
export const time = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
export const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
export const shortHash = (h: string, n = 10) => (h.length > n * 2 ? `${h.slice(0, n)}…${h.slice(-6)}` : h);
export const rid = (prefix: string, n = 6) => `${prefix}${Math.random().toString(36).slice(2, 2 + n)}`;
export const hex = (n: number) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, '0')).join('');
export const ms = (t: number) => (t < 1000 ? `${t.toFixed(t < 10 ? 1 : 0)} ms` : `${(t / 1000).toFixed(2)} s`);
