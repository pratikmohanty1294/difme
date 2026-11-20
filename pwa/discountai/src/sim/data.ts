import type { AuctionConfig, InvestorNode, Mandate, Split } from './types';

// Reference scenario from DAI-ARCH-E2E-v4.1 §0.4 (baseline worked example, instant checkout discount):
// List P = ₹10,000 · Bank X instant discount d = 20 % (₹2,000) · Merchant M charges A = ₹8,000 on the buying partner's
// Bank X agent token · buyer pays B = ₹8,800 to DiscountAI (MoR via Agent PA/PG) · buying partner payout A + Y = ₹8,600
// (Y = ₹600 yield) · platform fee F = ₹200 · buyer saves S = ₹1,200.
export const SCENARIO = {
  product: 'Noise-cancelling headphones',
  productDetail: 'Wireless noise-cancelling · Black · product P',
  query: 'Find the best price for Noise-cancelling headphones',
  merchant: 'Electronics Store A',
  category: 'Electronics',   // goods category checked against the buying partner's delegated instruction
  merchantId: 'M1',
  list: 10000,
  discountPct: 20,
  offerName: 'Bank X: 20% instant discount up to ₹2,000',
  requiredCard: 'Bank X Premium card',
  bin: '437551',
  mcc: '5732 · Electronics Stores',
  yield: 600,          // Y quoted by the winning buying partner (7.5 % of A)
  fee: 200,            // F, DiscountAI platform fee (10 % of it funds the Guarantee Fund)
  maxPrice: 9500,      // buyer's max_price in the POI (= price with the buyer's own card)
  intentTimeoutS: 30,
  buyerCard: { name: 'Bank X Millennial card', last4: '4242', cashbackPct: 5 },
  shipTo: 'Plot 42, Gachibowli, Hyderabad 500032',
};

/** v4.1 conservation identity, for any list price, discount and yield. */
export function splitOf(list: number, discountPct: number, Y: number, F: number): Split {
  const discount = Math.round((list * discountPct) / 100);
  const A = list - discount;
  const B = A + Y + F;
  return { list, discountPct, discount, A, Y, F, B, buyerSaves: list - B, payout: A + Y, guaranteeFund: Math.round(F * 0.1) };
}
export const SPLIT = splitOf(SCENARIO.list, SCENARIO.discountPct, SCENARIO.yield, SCENARIO.fee);

export const MERCHANTS = [
  { id: 'store-a', name: 'Electronics Store A', price: 10000, stock: 'In stock · ships today', promo: true },
  { id: 'mkt-a', name: 'Marketplace A', price: 10000, stock: 'In stock · 2-day', promo: false },
  { id: 'mkt-b', name: 'Marketplace B', price: 10000, stock: 'In stock · 3-day', promo: false },
  { id: 'store-b', name: 'Electronics Store B', price: 10290, stock: '3 units', promo: false },
];

export const DISCOUNT_RULES: Record<string, { category: string; pct: number; promo: string }[]> = {
  '437551': [
    { category: 'Electronics', pct: 20, promo: 'Instant · up to ₹2,000' },
    { category: 'E-gift cards (V2)', pct: 10, promo: 'Gift-eligible SKUs' },
    { category: 'Travel', pct: 12, promo: 'Cashback · 30 days' },
  ],
};

// Offer-liquidity variants
export const VARIANTS = [
  { id: 'V1', name: 'Issuer-sanctioned card marketplace', status: 'R', text: 'Bank X Offer-Liquidity Programme lets the buying partner\'s agent buy for a third-party buyer.' },
  { id: 'V2', name: 'Closed-loop e-gift cards', status: 'R', text: 'Buying partner buys Merchant M e-gift cards on Bank X; the code goes to the buyer from escrow. No shipping.' },
  { id: 'V3', name: 'Idle reward points → gift cards', status: 'A/R', text: 'Buying partner redeems Bank X points for M gift cards. No card authorisation; bootstrap liquidity.' },
] as const;

export const DEFAULT_CONFIG: AuctionConfig = {
  // v4.1 §3.2: flow-specific weights
  weights: {
    A: { w1: 0.5, w2: 0.4, w3: 0.1 },
    B: { w1: 0.6, w2: 0.3, w3: 0.1 },
    C: { w1: 0.7, w2: 0.3, w3: 0.0 },
  },
  batchWindowMs: 50,
  latencyMaxMs: 50,
  minRiskScore: 850,
  intentTimeoutS: 30,
  slashPct51: 2.0,
  slashPctOther: 0.5,
  depositPct: 5,
  vppMaxDays: 7,
  vickrey: false,
  newNodeReliability: 0.7,
};

// Card buying partners reproduce the v4.1 SD-C1 worked example: you (B) 0.736 · C 0.630 · A 0.560.
export const NODES: InvestorNode[] = [
  {
    id: 'IAgent-A', label: 'IAgent A · Card (risky)', owner: 'Meridian Capital', flow: 'A', assetClass: 'Bank X Premium card',
    region: 'US-East-1 · Virginia', yieldAskPct: 8.0, reliability: 0.6, latencyMs: 10, bin: '437551', zkCapable: false,
    mandateId: 'M-2026-7310', activeMandates: 14, queueDepth: 3, zkAcceptance: 1, fraudFlags: 1, online: true, depositInr: 2500, vppAgeDays: 3,
  },
  // The buying partner persona in Buying Partner. Its card node comes online when a Bank X mandate is deployed.
  {
    id: 'IAgent-Y', label: 'IAgent B · Card (you)', owner: 'You · Buying Partner', flow: 'A', assetClass: 'Bank X Premium card',
    region: 'AP-South-1 · Mumbai', yieldAskPct: 7.5, reliability: 0.99, latencyMs: 5, bin: '437551', zkCapable: false,
    mandateId: '—', activeMandates: 2, queueDepth: 0, zkAcceptance: 1, fraudFlags: 0, online: false, depositInr: 0, vppAgeDays: 0, isYou: true,
  },
  {
    id: 'IAgent-C', label: 'IAgent C · Card (slow)', owner: 'Northwind Treasury', flow: 'A', assetClass: 'Bank X Premium card',
    region: 'EU-West-1 · Frankfurt', yieldAskPct: 7.0, reliability: 0.9, latencyMs: 45, bin: '437551', zkCapable: false,
    mandateId: 'M-2026-6604', activeMandates: 9, queueDepth: 2, zkAcceptance: 1, fraudFlags: 0, online: true, depositInr: 2500, vppAgeDays: 5,
  },
  {
    id: 'IAgent-U', label: 'IAgent U · UPI', owner: 'Sahyadri Family Office', flow: 'C', assetClass: 'UPI Reserve Pay · SBMD',
    region: 'AP-South-1 · Mumbai', yieldAskPct: 6.0, reliability: 0.97, latencyMs: 8, zkCapable: false,
    mandateId: 'M-2026-5120', activeMandates: 6, queueDepth: 0, zkAcceptance: 1, fraudFlags: 0, online: true, depositInr: 1500, vppAgeDays: 0,
  },
  {
    id: 'IAgent-R', label: 'IAgent R · USDC', owner: 'Helvetia Digital Assets', flow: 'B', assetClass: 'USDC · Polygon escrow',
    region: 'EU-West-1 · Frankfurt', yieldAskPct: 6.5, reliability: 0.94, latencyMs: 12, zkCapable: true,
    mandateId: 'M-2026-8824', activeMandates: 2, queueDepth: 0, zkAcceptance: 0.991, fraudFlags: 0, online: true, depositInr: 2000, vppAgeDays: 0,
  },
];

const now = Date.now();
const iso = (minsAgo: number) => new Date(now - minsAgo * 60_000).toISOString();

export const SEED_MANDATES: Mandate[] = [
  {
    id: 'M-2026-8822', name: 'Gift-card portfolio (V2)', flow: 'A', assetType: 'FIAT_CREDIT', instrument: 'Bank X Premium card · e-gift SKUs', maxPerTx: 10000, minYieldPct: 5,
    mccRule: 'inclusion', mccs: ['Electronics'], regions: 'India', frequency: '3 / merchant / month', limit: 50000, utilised: 18000,
    yieldEarned: 1350, contract: 'MND_M2', version: 2, status: 'Paused', nodeId: 'IAgent-Y', createdAt: iso(60 * 24 * 14),
    deposit: 2500, variant: 'V2', intentType: 'Delegated budget · no human present', budgetTotal: 40000, merchants: ['Electronics Store A', 'Marketplace A'], goods: ['Gift cards', 'Electronics'], validityDays: 7, vppRefreshDue: iso(-60 * 24 * 2),
  },
  {
    id: 'M-2026-8823', name: 'UPI Reserve Pay block', flow: 'C', assetType: 'UPI_VAULT', instrument: 'Bank X savings · UPI Reserve Pay (SBMD)', maxPerTx: 12000, minYieldPct: 5,
    mccRule: 'inclusion', mccs: ['Grocery', 'Electronics', 'Travel'], regions: 'India', frequency: '5 / day', limit: 40000, utilised: 0,
    yieldEarned: 960, contract: 'NPCI-SBMD-55821', version: 1, status: 'Paused', nodeId: 'IAgent-U', createdAt: iso(60 * 24 * 9), deposit: 2000,
  },
];

export const YIELD_SERIES = [
  { d: 'Mon', flowA: 1180, flowB: 620, flowC: 220 },
  { d: 'Tue', flowA: 1310, flowB: 710, flowC: 250 },
  { d: 'Wed', flowA: 1260, flowB: 680, flowC: 240 },
  { d: 'Thu', flowA: 1490, flowB: 770, flowC: 310 },
  { d: 'Fri', flowA: 1630, flowB: 840, flowC: 180 },
  { d: 'Sat', flowA: 1710, flowB: 860, flowC: 0 },
  { d: 'Sun', flowA: 1840, flowB: 910, flowC: 0 },
];

export const ARCH_LAYERS = [
  { id: 'L7', name: 'Intent & Mandate Factory', text: 'Smart mandates · max_per_tx · min_yield_pct · budget · approved merchants[] · goods[] · validity ≤ 7 days · no human present' },
  { id: 'L6', name: 'Discovery & CLOB Matching', text: 'Buyer intent and buying partner liquidity books · SBBA scoring · <5 ms P99 match' },
  { id: 'L5', name: 'Multi-Rail Settlement Router', text: 'Buyer leg via Agent PA/PG · purchase leg at Merchant M via Merchant PSP' },
  { id: 'L4', name: 'Custody, Escrow & Guarantee Fund', text: 'Buyer auth-only hold B · capture on shipment · deposits · A + Y payouts and refunds' },
  { id: 'L3', name: 'RegTech, Risk & ZK Verifier', text: 'VaR / velocity / fraud ≥ 850 · ZK solvency proofs · 30-day EWMA reliability' },
  { id: 'L2', name: 'Agentic Protocol Gateway', text: 'MCP tools · AP2 · ACP / UCP checkout · signed POI submission' },
  { id: 'L1', name: 'Ultra-Low-Latency Data Fabric', text: 'Aeron 234.1.1.1:40123 · FIX SBE · CockroachDB ledger · Redpanda → IoRS' },
];
