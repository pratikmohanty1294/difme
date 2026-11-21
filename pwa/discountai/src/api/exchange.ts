// Typed client for the DIFME exchange API (api/openapi/difme.openapi.yaml, /v1/exchange). Same-origin: the PWA's web
// server proxies /api/ to exchange-svc, so no CORS and no API host baked into the bundle. Money is in minor units
// (paise) on the wire; the screens use rupees.

const BASE = '/api/v1/exchange';
const PACK = 'fr01-buy';

export class ExchangeError extends Error {
  constructor(readonly status: number, message: string, readonly body?: unknown) { super(message); }
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown, timeoutMs = 8000): Promise<T> {
  let r: Response;
  try {
    r = await fetch(BASE + path, {
      method,
      headers: body === undefined ? { Accept: 'application/json' } : { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch {
    throw new ExchangeError(0, 'The exchange is unreachable.');
  }
  const text = await r.text();
  const json = text ? (() => { try { return JSON.parse(text); } catch { return undefined; } })() : undefined;
  if (!r.ok) {
    const findings = (json as { findings?: { code: string }[] } | undefined)?.findings;
    const msg = (json as { error?: string } | undefined)?.error ?? (findings?.length ? findings.map((f) => f.code).join(', ') : `HTTP ${r.status}`);
    throw new ExchangeError(r.status, msg, json);
  }
  return json as T;
}

export interface Finding { code: string; action?: string; message?: string }
export interface IntentOpened { auctionId: number; maxYieldMinor: number; findings: Finding[]; standingBids?: number }
export interface SplitMinor { a: number; b: number; yield: number; fee: number; buyerSaves: number }
export interface Award { winner: { nodeRef: number; askMinor: number; score: number }; split: SplitMinor; collateralMinor: number; tier?: string }
export interface AuctionResult { auctionId: number; pack: string; ranked: number; top: { nodeRef: number; askMinor: number; score: number }[]; award: Award | null }
export interface Execution { auctionId: number; fulfilled: boolean; boughtBy: number; slashedMinor: number; attempts: { partnerId: number; responseCode: string }[]; escrow?: Hold }
export interface EvidenceArtifact { seq: number; type: string; signal: string; payload: Record<string, unknown>; prevHash: string; hash: string; postSeal: boolean }
export interface EvidenceView {
  chain: string; sealed: boolean; artifacts?: EvidenceArtifact[];
  verdict: { hashes: boolean; links: boolean; seal: boolean; witnesses: boolean; notes: string[] };
}
export interface Hold { holdId: string; heldMinor: number; releasedMinor: number; state: string }
export interface Fulfilment { auctionId: number; verified: boolean; problems: string[]; escrow: Hold; yieldReleaseAtMs: number }
export interface ReturnResult {
  auctionId: number; fractionBps: number; buyerRefundMinor: number; merchantRefundMinor: number; partnerClawbackMinor: number;
  yieldVoidedMinor: number; feeReversedMinor: number; escrow: Hold;
}
export interface MandateIn {
  partnerRef: string; limitMinor: number; maxPerOrderMinor: number; minYieldPct: number; depositMinor: number;
  budgetTotalMinor: number; merchantCount: number; goodsCount: number; validityDays: number; cardJurisdiction: string;
  /** The partner's own rules (same shape as sim/dmn-compile CustomTable); the exchange validates, compiles and runs them. */
  customRules?: import('../sim/dmn-compile').CustomTable;
}
export interface OrderInfo { merchant?: string; category?: string; brand?: string; mcc?: string; quantity?: number; city?: string; pincode?: string }
export interface CustomCheck { ok: boolean; problems?: string[]; outcome?: { action: 'APPROVE' | 'REJECT' | 'FLAG'; message: string; rule: number } }
export interface MandateRegistered { mandateId: number; depositRequiredMinor: number; findings: Finding[] }
export interface MandateState {
  mandateId: number; partnerRef: string; nodeRef: number; standing: boolean; budgetTotalMinor: number; budgetLeftMinor: number;
  awards: number; yieldEarnedMinor: number; minYieldPct: number; ageDays: number; validityDays: number; tier: string;
  status: 'ACTIVE' | 'PAUSED' | 'REVOKED';
}

export const exchange = {
  openIntent: (i: { riskScore: number; offerSanctioned: boolean; listMinor: number; discountBps: number; feeMinor: number; maxPriceMinor: number; order?: OrderInfo }) =>
    call<IntentOpened>('POST', `/${PACK}/intents`, i),
  auction: (id: number) => call<AuctionResult>('GET', `/auctions/${id}`),
  execute: (id: number, merchantId: string) => call<Execution>('POST', `/auctions/${id}/execute`, { merchantId }),
  evidence: (id: number) => call<EvidenceView>('GET', `/auctions/${id}/evidence`),
  /** Dev clusters: the simulated merchant and carrier deliver and submit their signed proof of delivery. */
  simDelivery: (id: number) => call<Fulfilment>('POST', `/auctions/${id}/sim/delivery`),
  /** FR-01 return inside the return window: full (10000) or partial (fractionBps of the order). */
  returnOrder: (id: number, fractionBps = 10_000) => call<ReturnResult>('POST', `/auctions/${id}/returns`, { fractionBps }),
  registerMandate: (m: MandateIn) => call<MandateRegistered>('POST', `/${PACK}/mandates`, m),
  /** Validates a custom rules table and runs it on a sample order (the factory's preview). */
  checkCustomRules: (table: import('../sim/dmn-compile').CustomTable, order: Record<string, unknown>) =>
    call<CustomCheck>('POST', '/custom-rules/check', { table, order }),
  mandate: (id: number) => call<MandateState>('GET', `/${PACK}/mandates/${id}`),
  /** Pause (no new bids), resume, or revoke (final). */
  setMandateStatus: (id: number, status: MandateState['status']) => call<MandateState>('POST', `/${PACK}/mandates/${id}/status`, { status }),
  /** Cheap reachability probe (the public verification keys). */
  ping: () => call<Record<string, string>>('GET', '/keys', undefined, 3000).then(() => true, () => false),
};

/** Polls the auction until it has a result (award or none ranked); the batch window is 50 ms, so this is quick. */
export async function awaitResult(id: number, timeoutMs = 10_000): Promise<AuctionResult> {
  const end = Date.now() + timeoutMs;
  for (;;) {
    try {
      const r = await exchange.auction(id);
      if (r.award || (r.ranked !== undefined && r.ranked !== null && r.top !== undefined)) return r;
    } catch (e) {
      if (!(e instanceof ExchangeError) || (e.status !== 404 && e.status !== 409)) throw e; // still open
    }
    if (Date.now() > end) throw new ExchangeError(408, 'The auction did not close in time.');
    await new Promise((r) => setTimeout(r, 60));
  }
}

/** Role builds (shopbot / investor / exchange) run against the live exchange; the full web build keeps the demo simulation. */
export const LIVE = import.meta.env.VITE_APP !== 'web';
