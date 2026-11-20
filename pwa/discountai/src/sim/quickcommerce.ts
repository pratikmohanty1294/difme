// Live store prices are not part of the DIFME build: the prototype's price feed was a third-party API with a
// usage-capped free tier, which the open-source/no-limitations rule excludes. This module keeps the interface the
// buyer screens use and always reports "not configured", so they fall back to the seeded catalogue. Prices for
// real orders come from the merchant checkout rail of the exchange (services/core/exchange-rails).

export interface QcBudget { paidAllowed: boolean; reason?: string; siteLeftToday: number; visitorLeftToday: number; credits: number | null; reserve: number }
export interface QcStatus { configured: boolean; plan: string; mcp: string; tools: Record<string, number>; searchPlatforms: string[]; budget?: QcBudget }
export interface QcResult<T = unknown> {
  ok: boolean; tool?: string; cost?: number; source?: 'cache' | 'similar' | 'live'; ageMin?: number; similarTo?: string;
  data?: T; error?: string; needsPaid?: boolean; budget?: QcBudget;
}

/** No outbound store links (store and marketplace names are company names). */
export const STORE_SEARCH: Record<string, (q: string) => string> = {};

export interface LiveProduct {
  id: string; name: string; brand?: string; price: number; mrp?: number; currency: string;
  available: boolean; rating?: number; quantity?: string; deeplink?: string; image?: string; platform: string; sla?: string;
}

export const CITIES = [
  { id: 'blr', name: 'Bengaluru', lat: 12.9716, lon: 77.5946, pincode: '560001' },
  { id: 'bom', name: 'Mumbai', lat: 19.076, lon: 72.8777, pincode: '400001' },
  { id: 'del', name: 'Delhi', lat: 28.6139, lon: 77.209, pincode: '110001' },
  { id: 'hyd', name: 'Hyderabad', lat: 17.385, lon: 78.4867, pincode: '500001' },
  { id: 'maa', name: 'Chennai', lat: 13.0827, lon: 80.2707, pincode: '600001' },
] as const;

export const FALLBACK_PLATFORMS = ['Marketplace A', 'Marketplace B', 'Quick commerce A', 'Quick commerce B', 'Grocery A', 'Fashion A'];

export async function qcStatus(): Promise<QcStatus | null> {
  return null;
}

export async function qcCall<T = unknown>(_tool: string, _args: Record<string, unknown> = {}, _paid = false): Promise<QcResult<T>> {
  return { ok: false, error: 'Live store prices are not available in this build.' };
}

const num = (v: unknown) => (typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v)) ? Number(v) : undefined);

/** Finds "remaining credits" in an answer (kept for the screens' types; nothing produces credits here). */
export function creditsFrom(data: unknown): number | undefined {
  if (!data || typeof data !== 'object') return undefined;
  const d = data as Record<string, unknown>;
  for (const k of ['credits_remaining', 'total_remaining', 'remaining', 'credits', 'balance']) {
    const v = num(d[k]); if (v !== undefined) return v;
  }
  return undefined;
}

/** Normalises a product list answer into LiveProduct entries, in-stock first, keeping the source order. */
export function productsFrom(data: unknown, fallbackPlatform: string): LiveProduct[] {
  const root = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  const list = Array.isArray(root.products) ? root.products : [];
  const out: LiveProduct[] = [];
  for (const p of list as Record<string, unknown>[]) {
    const price = num(p.offer_price) ?? num(p.price) ?? num(p.mrp);
    if (price === undefined || !p.name) continue;
    out.push({ id: String(p.id ?? p.name), name: String(p.name), price, mrp: num(p.mrp), currency: String(p.currency ?? 'INR'),
      available: p.available !== false, platform: String(p.platform ?? fallbackPlatform) });
  }
  return out.map((p, i) => ({ p, i })).sort((a, b) => Number(b.p.available) - Number(a.p.available) || a.i - b.i).map((x) => x.p);
}

export const money = (n: number, currency: string) =>
  new Intl.NumberFormat(currency === 'AED' ? 'en-AE' : 'en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(n);
