import { LIVE, exchange, awaitResult, ExchangeError } from '../api/exchange';
import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, type ReactNode } from 'react';
import { DEFAULT_CONFIG, NODES, SCENARIO, SEED_MANDATES, splitOf } from './data';
import { makeProof, sha256, verifyChain } from './crypto';
import { hex, inr, rid } from './format';
import { rankBids, round, scoreBid } from './sbba';
import { evaluate, evaluateCustom } from './rules';
import { vic, VIC_MODE_LABEL } from './vic';

import { buildOrderEvidence, buildSetupChain, type AttemptInput, type OrderInput } from './evidence';

/** the card network purchase instructions registered on demand for demo buying partners that have no mandate of their own. */
const vicInstructions = new Map<string, string>();
/** The confirmation mode recorded in Chain B. */
const prefsMode = (p: BuyerPrefs | undefined, skipped?: boolean) =>
  skipped ? 'not asked (operator test or narrated demo)' : !p ? 'not chosen' : `${p.mode}${p.region === 'EU' ? ' + EU per-order confirmation' : ''}`;
import type {
  AuctionConfig, AuditEntry, Bid, BuyRequest, BuyerPrefs, FeedItem, InvestorNode, Mandate, Notice, Outcome, ProofObject,
  RefundCase, RefundRecord, RegulatorEvent, SettlementRoute, Speed, Split, TimelineStep,
} from './types';

// DAI-ARCH-E2E-v4.1 simulation: investor-as-purchaser, dual PA/PG, two-phase commit, silent failover,
// Guarantee Fund backstop, escrow payout of A + Y, and two-leg refunds. All money in ₹.

interface State {
  config: AuctionConfig;
  configAudit: AuditEntry[];
  nodes: InvestorNode[];
  mandates: Mandate[];
  requests: BuyRequest[];
  feed: FeedItem[];
  regulator: RegulatorEvent[];
  notifications: Notice[];
  guaranteeFund: number;
  speed: Speed;
  buyerPrefs?: BuyerPrefs;   // the buyer's first-use confirmation choice (automated contracting)
}

type Action =
  | { type: 'addRequest'; req: BuyRequest }
  | { type: 'patchRequest'; id: string; patch: Partial<BuyRequest> }
  | { type: 'step'; id: string; step: TimelineStep }
  | { type: 'bid'; id: string; bid: Bid }
  | { type: 'bids'; id: string; bids: Bid[] }
  | { type: 'proof'; id: string; proof: ProofObject }
  | { type: 'refund'; id: string; refund: RefundRecord }
  | { type: 'feed'; item: FeedItem }
  | { type: 'regulator'; ev: RegulatorEvent }
  | { type: 'notify'; notice: Notice }
  | { type: 'config'; config: AuctionConfig; entry: AuditEntry }
  | { type: 'mandate'; mandate: Mandate }
  | { type: 'patchMandate'; id: string; patch: Partial<Mandate> }
  | { type: 'node'; id: string; patch: Partial<InvestorNode> }
  | { type: 'gf'; delta: number }
  | { type: 'speed'; speed: Speed }
  | { type: 'buyerPrefs'; prefs: BuyerPrefs }
  | { type: 'reset'; state: State };

const mapReq = (s: State, id: string, f: (r: BuyRequest) => BuyRequest) => ({ ...s, requests: s.requests.map((r) => (r.id === id ? f(r) : r)) });

function reducer(s: State, a: Action): State {
  switch (a.type) {
    case 'addRequest': return { ...s, requests: [a.req, ...s.requests] };
    case 'patchRequest': return mapReq(s, a.id, (r) => ({ ...r, ...a.patch }));
    case 'step': return mapReq(s, a.id, (r) => ({ ...r, timeline: [...r.timeline, a.step] }));
    case 'bid': return mapReq(s, a.id, (r) => ({ ...r, bids: [...r.bids, a.bid] }));
    case 'bids': return mapReq(s, a.id, (r) => ({ ...r, bids: a.bids }));
    case 'proof': return mapReq(s, a.id, (r) => ({ ...r, proofs: [...r.proofs, a.proof] }));
    case 'refund': return mapReq(s, a.id, (r) => ({ ...r, refunds: [...(r.refunds ?? []), a.refund] }));
    case 'feed': return { ...s, feed: [a.item, ...s.feed].slice(0, 60) };
    case 'regulator': return { ...s, regulator: [a.ev, ...s.regulator].slice(0, 80) };
    case 'notify': return { ...s, notifications: [a.notice, ...s.notifications].slice(0, 120) };
    case 'config': return { ...s, config: a.config, configAudit: [a.entry, ...s.configAudit] };
    case 'mandate': return { ...s, mandates: [a.mandate, ...s.mandates] };
    case 'patchMandate': return { ...s, mandates: s.mandates.map((m) => (m.id === a.id ? { ...m, ...a.patch } : m)) };
    case 'node': return { ...s, nodes: s.nodes.map((n) => (n.id === a.id ? { ...n, ...a.patch } : n)) };
    case 'gf': return { ...s, guaranteeFund: round(s.guaranteeFund + a.delta) };
    case 'speed': return { ...s, speed: a.speed };
    case 'buyerPrefs': return { ...s, buyerPrefs: a.prefs };
    case 'reset': return a.state;
  }
}

/** Kept for pages that compute splits; the identity lives in data.ts. */
export const splitFor = splitOf;

export function routeFor(node: InvestorNode): SettlementRoute {
  if (node.flow === 'A') return 'Flow A · Card (V1)';
  if (node.flow === 'C') return 'Flow C · UPI Reserve Pay';
  return 'Flow B · USDC';
}

/** Two-leg refund amounts for a settled order. */
export function refundFor(s: Split, kind: RefundCase): Omit<RefundRecord, 'at'> {
  if (kind === 'partial') {
    const rm = round(s.A / 2);
    return {
      case: kind, merchantRefundToInvestor: rm, buyerRefund: round((rm / s.A) * s.B), investorClawback: round((rm / s.A) * (s.A + s.Y)),
      feeReversal: round(s.F / 2), recovery: 'Proportional: netted against the buying partner\'s next escrow payout',
    };
  }
  if (kind === 'post-payout') {
    return {
      case: kind, merchantRefundToInvestor: s.A, buyerRefund: s.B, investorClawback: s.A + s.Y, feeReversal: s.F,
      recovery: 'Next escrow payout netting → NACH e-mandate debit → T+3 lien on the security deposit',
    };
  }
  return {
    case: kind, merchantRefundToInvestor: s.A, buyerRefund: s.B, investorClawback: 0, feeReversal: s.F,
    recovery: `Pending ${inr(s.payout)} payout frozen; unearned yield ${inr(s.Y)} and fee ${inr(s.F)} voided`,
  };
}

// ---------- seed ----------
function seedState(): State {
  const iso = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
  const base = (o: Partial<BuyRequest>): BuyRequest => ({
    id: rid('REQ-', 5).toUpperCase(), auctionId: rid('auc_', 7), product: '', icon: 'headphones', merchant: 'Electronics Store A', mcc: SCENARIO.mcc,
    instrument: SCENARIO.requiredCard, bin: SCENARIO.bin, list: 0, quoteB: 0, maxPrice: 0, expiry: '00:30',
    createdAt: iso(60), status: 'Success', outcome: 'success', riskScore: 0, timeline: [], bids: [], proofs: [], seeded: true, ...o,
  });
  const speaker = splitOf(5000, 20, 300, 100);
  const watch = splitOf(12000, 20, 720, 200);
  const requests: BuyRequest[] = [
    base({
      product: 'Portable speaker', icon: 'sparkles', merchant: 'Electronics Store A', list: 5000, quoteB: speaker.B, maxPrice: 4750,
      createdAt: iso(26 * 60), status: 'Success', riskScore: 891, winnerNodeId: 'IAgent-C', backupIds: ['IAgent-A'], route: 'Flow A · Card (V1)', split: speaker,
      orderId: 'CRM-88124', trackingId: 'BLUEDART-77310', chainValid: true, payoutDone: true, buyerHoldId: 'chg_agpa_4400',
      timeline: [
        { t: 0, actor: 'Buyer Agent', label: 'POI · signed buy request submitted', phase: 'POI', step: '24' },
        { t: 50, actor: 'OME', label: 'POL · IAgent C won (score 0.645)', phase: 'POL', step: '30' },
        { t: 900, actor: 'Agent PA/PG', label: `PRL · buyer auth-only hold ${inr(speaker.B)}`, phase: 'PRL', step: '33' },
        { t: 6200, actor: 'Merchant PSP', label: `POE · ISO 0110 approved ${inr(speaker.A)} · shipped`, phase: 'POE', step: '44–49' },
        { t: 7400, actor: 'Compliance', label: `POC · captured ${inr(speaker.B)}, paid buying partner ${inr(speaker.payout)}`, phase: 'POC', step: '50–56' },
      ],
    }),
    base({
      product: 'Fitness band', icon: 'monitor', merchant: 'Electronics Store A', list: 12000, quoteB: watch.B, maxPrice: 11400,
      createdAt: iso(2 * 24 * 60), status: 'Refunded', riskScore: 866, winnerNodeId: 'IAgent-A', route: 'Flow A · Card (V1)', split: watch,
      orderId: 'CRM-80611', trackingId: 'DELHIVERY-22019', payoutDone: false, buyerHoldId: 'chg_agpa_10520',
      refunds: [{ at: iso(24 * 60), ...refundFor(watch, 'pre-payout') }],
      failReason: `Returned before the buying partner payout (Case 1). Electronics Store A refunded ${inr(watch.A)} to the buying partner's Bank X card; DiscountAI refunded your ${inr(watch.B)} straight away.`,
      timeline: [
        { t: 0, actor: 'Buyer Agent', label: 'POI · signed buy request submitted', phase: 'POI', step: '24' },
        { t: 50, actor: 'OME', label: 'POL · IAgent A won', phase: 'POL', step: '30' },
        { t: 7000, actor: 'Merchant PSP', label: `POE · ${inr(watch.A)} approved and shipped`, phase: 'POE', step: '44–49' },
        { t: 9000, actor: 'Agent PA/PG', label: `Return · ${inr(watch.B)} refunded to buyer (Case 1)`, phase: 'REFUND', step: '57a–b' },
      ],
    }),
    base({
      product: 'E-reader', icon: 'monitor', merchant: 'Marketplace A', list: 14000, quoteB: 0, maxPrice: 13300,
      createdAt: iso(3 * 24 * 60), status: 'Timed Out', outcome: 'no-match', riskScore: 858,
      failReason: 'No buying partner bid met the request before it expired. Nothing was charged: your hold is only placed after a match.',
      timeline: [
        { t: 0, actor: 'Buyer Agent', label: 'POI · signed buy request submitted', phase: 'POI', step: '24' },
        { t: 50, actor: 'OME', label: 'Batch closed · 0 eligible bids', phase: 'FAIL', step: '30' },
      ],
    }),
  ];
  const feed: FeedItem[] = [
    { id: rid('f'), at: iso(1), kind: 'bid', text: 'Bid submitted · auc_9f2k1ma · e-gift card portfolio · ₹600', nodeId: 'IAgent-Y' },
    { id: rid('f'), at: iso(4), kind: 'won', text: 'Won auc_x81bq0d · Electronics Store A e-gift card (V2)', amount: 450, nodeId: 'IAgent-Y' },
    { id: rid('f'), at: iso(9), kind: 'outbid', text: 'Outbid on auc_p02nn7c by IAgent C (0.645 vs 0.611)', nodeId: 'IAgent-Y' },
  ];
  return {
    config: DEFAULT_CONFIG,
    configAudit: [{ at: iso(3 * 24 * 60), who: 'ops.admin', change: 'Initial weights (A 0.5/0.4/0.1 · B 0.6/0.3/0.1 · C 0.7/0.3/0.0), Lmax 50 ms' }],
    nodes: NODES,
    mandates: SEED_MANDATES,
    requests,
    feed,
    regulator: [],
    notifications: [],
    guaranteeFund: 125000,
    speed: 'presenter',
  };
}

// ---------- context ----------
interface Ctx {
  state: State;
  /** skipConfirm: operator test auctions and the narrated guided demo, where no buyer is present to confirm. */
  submitRequest: (o: { maxPrice: number; expiry: string; outcome: Outcome; skipConfirm?: boolean }) => string;
  refundRequest: (id: string, kind: RefundCase) => void;
  setConfig: (cfg: AuctionConfig, change: string) => void;
  addMandate: (m: Mandate) => void;
  patchMandate: (id: string, patch: Partial<Mandate>) => void;
  setSpeed: (s: Speed) => void;
  reset: () => void;
  setBuyerPrefs: (p: BuyerPrefs) => void;
  /** The buyer's answer to an order waiting for confirmation. */
  confirmQuote: (id: string, yes: boolean) => void;
}

const EngineCtx = createContext<Ctx | null>(null);

export function useEngine() {
  const c = useContext(EngineCtx);
  if (!c) throw new Error('EngineProvider missing');
  return c;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function EngineProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, undefined, seedState);
  const ref = useRef(state);
  ref.current = state;
  const runId = useRef(0);
  const pendingConfirm = useRef(new Map<string, (yes: boolean) => void>());

  // Build a real signed proof chain for the seeded success, so the audit view is populated at rest.
  const seeding = useRef(new Set<string>());
  useEffect(() => {
    const seeded = state.requests.find((r) => r.seeded && r.status === 'Success' && r.proofs.length === 0);
    if (!seeded || seeding.current.has(seeded.id)) return;
    seeding.current.add(seeded.id);
    (async () => {
      const proofs = await chainFor(seeded, 0.645);
      for (const p of proofs) dispatch({ type: 'proof', id: seeded.id, proof: p });
      const s = seeded.split;
      if (!s) return;
      const winner = seeded.winnerNodeId ?? 'IAgent-C';
      const setup = await buildSetupChain({ mandateId: `M-${winner}`, investorId: winner, budgetTotal: 40000, maxPerOrder: 25000, merchants: [seeded.merchant, 'Electronics Store B'], goods: [SCENARIO.category], validityDays: 7, instructionId: `seed:${winner}` });
      const evidenceInput: OrderInput = {
        orderId: seeded.id, product: seeded.product, merchant: seeded.merchant, maxPrice: seeded.maxPrice, shipHash: await sha256(SCENARIO.shipTo),
        dfaMode: 'always', B: s.B, A: s.A, Y: s.Y, F: s.F, holdId: seeded.buyerHoldId ?? 'chg_agpa_seed',
        attempts: [{ investorId: winner, instructionId: `seed:${winner}`, approved: true, authCode: '5570C1' }], tracking: seeded.trackingId ?? 'BLUEDART-70111', setup,
      };
      dispatch({ type: 'patchRequest', id: seeded.id, patch: { evidence: await buildOrderEvidence(evidenceInput), evidenceInput } });
    })();
  }, [state.requests]);

  // Ambient market activity for the buying partner's live race feed.
  useEffect(() => {
    if (LIVE) return; // role builds show only what the live exchange reports
    const t = setInterval(() => {
      const r = Math.random();
      const auc = rid('auc_', 7);
      const store = ['Electronics Store A', 'Electronics Store B', 'Group T CLiQ', 'Vijay Sales'][Math.floor(Math.random() * 4)];
      const item: FeedItem =
        r < 0.55
          ? { id: rid('f'), at: new Date().toISOString(), kind: 'bid', text: `Bid submitted · ${auc} · ${store} · 7.5% of A`, nodeId: 'IAgent-Y' }
          : r < 0.8
            ? { id: rid('f'), at: new Date().toISOString(), kind: 'won', text: `Won ${auc} · ${store} e-gift card (V2)`, amount: Math.round(150 + Math.random() * 600), nodeId: 'IAgent-Y' }
            : { id: rid('f'), at: new Date().toISOString(), kind: 'outbid', text: `Outbid on ${auc} by IAgent C`, nodeId: 'IAgent-Y' };
      dispatch({ type: 'feed', item });
    }, 6500);
    return () => clearInterval(t);
  }, []);

  // Live builds: read each registered mandate back from the exchange (what it spent, won and earned; still standing?).
  useEffect(() => {
    if (!LIVE) return;
    const t = setInterval(() => {
      for (const m of ref.current.mandates) {
        if (!m.exchangeMandateId) continue;
        exchange.mandate(m.exchangeMandateId).then((s) => dispatch({ type: 'patchMandate', id: m.id, patch: {
          utilised: (s.budgetTotalMinor - s.budgetLeftMinor) / 100, yieldEarned: s.yieldEarnedMinor / 100, status: s.standing ? 'LIVE' : 'Paused',
        } }), () => undefined);
      }
    }, 10_000);
    return () => clearInterval(t);
  }, []);

  const pace = useCallback(async (base: number) => {
    const f = ref.current.speed === 'presenter' ? 1 : ref.current.speed === 'normal' ? 0.35 : 0;
    await sleep(base * f);
  }, []);

  /**
   * Live path (role builds): the buy request goes to the exchange. The intent opens a 50 ms batch auction, standing
   * mandates bid as resting orders, the award is executed over the rails, and the evidence chain is verified. Each
   * state lands on the same request record the screens already render.
   */
  const liveSubmit = useCallback((maxPrice: number, expiry: string, outcome: Outcome): string => {
    const P = SCENARIO.list;
    const dP = Math.round((P * SCENARIO.discountPct) / 100);
    const req: BuyRequest = {
      id: rid('REQ-', 5).toUpperCase(), auctionId: '…', product: SCENARIO.product, icon: 'headphones', merchant: SCENARIO.merchant,
      mcc: SCENARIO.mcc, instrument: SCENARIO.requiredCard, bin: SCENARIO.bin, list: P, quoteB: 0, maxPrice, expiry,
      createdAt: new Date().toISOString(), status: 'Pending', outcome, riskScore: 900, timeline: [], bids: [], proofs: [],
    };
    dispatch({ type: 'addRequest', req });
    const id = req.id;
    const t0 = performance.now();
    const at = () => Math.round((performance.now() - t0) / 100) / 10;
    const step = (actor: TimelineStep['actor'], label: string, phase: TimelineStep['phase'], detail?: string) =>
      dispatch({ type: 'step', id, step: { t: at(), actor, label, phase, detail } });
    const fail = (why: string) => {
      dispatch({ type: 'patchRequest', id, patch: { status: 'Timed Out', failReason: why } });
      step('OME', why, 'POC');
    };
    (async () => {
      try {
        const opened = await exchange.openIntent({ riskScore: req.riskScore, offerSanctioned: true, listMinor: P * 100,
          discountBps: SCENARIO.discountPct * 100, feeMinor: SCENARIO.fee * 100, maxPriceMinor: maxPrice * 100 });
        dispatch({ type: 'patchRequest', id, patch: { auctionId: `auc_${opened.auctionId}` } });
        step('Buyer Agent', `Intent accepted by the exchange · auction ${opened.auctionId}`, 'POI',
          `${opened.standingBids ?? 0} standing mandate bid(s) · max yield ${inr(opened.maxYieldMinor / 100)}`);
        const r = await awaitResult(opened.auctionId);
        if (!r.award) return fail(r.ranked === 0 ? 'No buying partner matched this request' : 'The auction closed without an award');
        const s = r.award.split;
        const split: Split = { list: P, discountPct: SCENARIO.discountPct, discount: dP, A: s.a / 100, Y: s.yield / 100, F: s.fee / 100,
          B: s.b / 100, buyerSaves: s.buyerSaves / 100, payout: (s.a + s.yield) / 100, guaranteeFund: Math.round(s.fee / 10) / 100 };
        dispatch({ type: 'patchRequest', id, patch: { status: 'Matched', split, quoteB: split.B, winnerNodeId: `node-${r.award.winner.nodeRef}`,
          backupIds: r.top.slice(1).map((b) => `node-${b.nodeRef}`) } });
        step('OME', `Awarded to node ${r.award.winner.nodeRef} of ${r.ranked} ranked`, 'POL', `A ${inr(split.A)} · Y ${inr(split.Y)} · F ${inr(split.F)} · you pay ${inr(split.B)}`);
        const e = await exchange.execute(opened.auctionId, SCENARIO.merchantId);
        if (!e.fulfilled) return fail('No buying partner could complete the purchase; your hold was refunded');
        dispatch({ type: 'patchRequest', id, patch: { status: 'Success', orderId: `ORD-${opened.auctionId}` } });
        step('Merchant', `Bought by node ${e.boughtBy}${e.attempts.length > 1 ? ` after ${e.attempts.length - 1} failover(s)` : ''}`, 'POE');
        const ev = await exchange.evidence(opened.auctionId);
        const ok = ev.verdict.hashes && ev.verdict.links && ev.verdict.seal && ev.verdict.witnesses;
        dispatch({ type: 'patchRequest', id, patch: { chainValid: ok } });
        step('Compliance', ok ? 'Evidence chain verified (hashes, links, operator seal, witness)' : `Evidence chain did not verify: ${ev.verdict.notes.join('; ')}`, 'POC');
        // Delivery: merchant invoice + carrier event, each signed by its own registered key and checked by the exchange.
        // Fee F is released now; yield Y only after the pack's return window.
        const f = await exchange.simDelivery(opened.auctionId);
        if (!f.verified) return step('Compliance', `Proof of delivery refused: ${f.problems.join('; ')}`, 'POC');
        const releaseAt = new Date(f.yieldReleaseAtMs).toISOString();
        dispatch({ type: 'patchRequest', id, patch: { trackingId: `TRK-${opened.auctionId}`, payoutPlan: {
          tier: r.award.tier === 'COLD_START' ? 0 : 1, principal: split.A, yield: split.Y, principalTo: `node-${e.boughtBy}`,
          principalPaidAt: new Date().toISOString(), releaseAt } } });
        step('Merchant', `Delivered · signed invoice and carrier event verified`, 'POC',
          `fee ${inr(split.F)} released · yield ${inr(split.Y)} releases ${new Date(f.yieldReleaseAtMs).toLocaleDateString('en-IN')} after the return window`);
      } catch (err) {
        fail(err instanceof ExchangeError ? (err.status === 0 ? 'The exchange is unreachable; nothing was charged' : `Refused by the exchange: ${err.message}`) : String(err));
      }
    })();
    return id;
  }, []);

  const submitRequest = useCallback<Ctx['submitRequest']>(({ maxPrice, expiry, outcome, skipConfirm }) => {
    if (LIVE) return liveSubmit(maxPrice, expiry, outcome);
    const myRun = runId.current;
    const cfg = ref.current.config;
    const P = SCENARIO.list;
    const dP = Math.round((P * SCENARIO.discountPct) / 100);
    const A = P - dP;
    const req: BuyRequest = {
      id: rid('REQ-', 5).toUpperCase(), auctionId: rid('auc_', 7), product: SCENARIO.product, icon: 'headphones', merchant: SCENARIO.merchant,
      mcc: SCENARIO.mcc, instrument: SCENARIO.requiredCard, bin: SCENARIO.bin, list: P, quoteB: 0, maxPrice, expiry,
      createdAt: new Date().toISOString(), status: 'Pending', outcome, riskScore: 850 + Math.floor(Math.random() * 60), timeline: [], bids: [], proofs: [],
    };
    dispatch({ type: 'addRequest', req });
    const id = req.id;
    const alive = () => runId.current === myRun;
    const step = (t: number, actor: TimelineStep['actor'], label: string, phase: TimelineStep['phase'], detail?: string, stepNo?: string) =>
      alive() && dispatch({ type: 'step', id, step: { t, actor, label, phase, detail, step: stepNo } });
    const reg = (proof: RegulatorEvent['proof'], text: string) =>
      alive() && dispatch({ type: 'regulator', ev: { at: new Date().toISOString(), proof, requestId: id, latencyMs: 90 + Math.floor(Math.random() * 300), text } });
    const notify = (party: Notice['party'], title: string, body: string, extra?: Partial<Notice>) =>
      alive() && dispatch({ type: 'notify', notice: { id: rid('n'), at: new Date().toISOString(), party, title, body, requestId: id, ...extra } });
    const node = (nid: string) => ref.current.nodes.find((n) => n.id === nid)!;

    (async () => {
      // ---- Phase 2–3 · POI ----
      const shipHash = (await sha256(SCENARIO.shipTo)).slice(0, 16);
      const poi = await makeProof('POI', 'Proof-of-Intent', 'Buyer Agent (ECDSA) + TAP signature', {
        intent_id: id, product_id: 'prod-001', product: SCENARIO.product, merchants: ['M1', 'M2'], max_price: maxPrice, currency: 'INR',
        offer: SCENARIO.offerName, required_bin: SCENARIO.bin, ship_to_hash: shipHash, deadline: `${cfg.intentTimeoutS}s`, buyer_agent: 'shopbot.agent/7f21',
      });
      if (!alive()) return;
      dispatch({ type: 'proof', id, proof: poi });
      step(0, 'Buyer Agent', 'Signed buy request (POI) submitted', 'POI', `${SCENARIO.product} · max_price ${inr(maxPrice)} · ship-to hashed`, '24');
      reg('POI', `POI for ${id} received`);
      notify('Buyer', 'Request sent to the exchange', `ShopBot is looking for a buying partner whose agent will buy it with their Bank X card and ship it to you. You'll pay at most ${inr(maxPrice)}, and nothing is held until there's a match.`, { tone: 'info' });
      await pace(500);
      step(1.4, 'Risk Engine', `L3 VaR · velocity · fraud check → ${req.riskScore} ≥ ${cfg.minRiskScore}`, 'POI', 'Approved for broadcast', '25');
      await pace(450);

      // ---- Pre-match filtering ----
      const online = ref.current.nodes.filter((n) => n.online);
      step(2.0, 'OME', `Pre-match filter · ₹0 card check via Agent PA/PG · purchase instruction still valid · deposit posted`, 'POL',
        online.filter((n) => n.flow === 'A').map((n) => `${n.id.replace('IAgent-', '')}: instruction ${n.vppAgeDays}d old · deposit ${inr(n.depositInr)}`).join(' · '), '26–26a');
      await pace(400);
      step(2.4, 'Merchant', `Offer snapshot locked at ${SCENARIO.merchant} (UCIP /checkouts) · ${SCENARIO.offerName}`, 'POL', `A = ${inr(A)} on Bank X`, '27');
      await pace(350);
      step(2.6, 'Aeron', `Auction ${req.auctionId} · 128-byte SBE frame on 234.1.1.1:40123 · Stream 1001`, 'POL', 'Buying partner agents answer from local cache only', '28');
      await pace(450);

      // ---- 50 ms SBBA ----
      // Per-auction buying partner eligibility runs in the DMN rule engine (rules/investor_eligibility.dmn).
      // The order must fit each buying partner's delegated instruction: validity, budget left, approved merchant, goods in scope.
      const elig = await Promise.all(online.map((n) => {
        const m = ref.current.mandates.find((x) => x.nodeId === n.id && x.status === 'LIVE');
        return evaluate('investor_eligibility', {
          flow: n.flow, intentAgeDays: n.vppAgeDays, validityDays: m?.validityDays ?? cfg.vppMaxDays, deposit: n.depositInr, amountA: A,
          budgetLeft: m ? (m.budgetTotal ?? m.limit) - m.utilised : 25000,
          merchantApproved: m?.merchants ? m.merchants.includes(SCENARIO.merchant) : true,
          goodsInScope: m?.goods ? m.goods.includes(SCENARIO.category) : true,
          yieldAsk: Math.round((A * n.yieldAskPct) / 100), maxDepositNeeded: Math.round(A * 0.05),
          maxPerOrder: m?.maxPerTx ?? 25000, yieldRoom: maxPrice - A - SCENARIO.fee,
        }, 2500);
      }));
      if (!alive()) return;
      const src = elig.some((c) => c.source === 'fluxnova') ? 'FINOS Fluxnova' : elig.some((c) => c.source === 'edge') ? 'the edge DMN engine' : 'local DMN fallback';
      step(2.7, 'OME', `Buying partner eligibility · investor_eligibility.dmn on ${src}`, 'POL',
        `${elig.filter((c) => c.ok).length} of ${online.length} online nodes eligible · ${Math.max(0, ...elig.map((c) => c.ms))} ms`, '26a');
      // Each buying partner's own custom fields and rules (built in the mandate factory) run on this order in the rule engine.
      const now = new Date();
      const ist = new Date(now.getTime() + (330 + now.getTimezoneOffset()) * 60000);
      const orderCtx = {
        amount: A, listPrice: P, discountPct: SCENARIO.discountPct, merchant: SCENARIO.merchant, category: SCENARIO.category, brand: 'Brand H',
        mcc: SCENARIO.mcc.split(' ')[0], quantity: 1, city: 'Hyderabad', pincode: (SCENARIO.shipTo.match(/\d{6}/) ?? ['500032'])[0],
        hour: ist.getHours(), weekday: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][ist.getDay()], buyerRiskScore: req.riskScore,
      };
      const custom = await Promise.all(online.map((n) => {
        const m = ref.current.mandates.find((x) => x.nodeId === n.id && x.status === 'LIVE');
        return m?.customRules ? evaluateCustom(m.customRules, m.id, { ...orderCtx, yieldPct: n.yieldAskPct }, { timeoutMs: 4000 }) : Promise.resolve(undefined);
      }));
      if (!alive()) return;
      const withCustom = custom.filter(Boolean);
      if (withCustom.length) {
        const csrc = withCustom.some((c) => c!.source === 'fluxnova') ? 'FINOS Fluxnova' : withCustom.some((c) => c!.source === 'edge') ? 'the edge DMN engine' : 'no engine (unreachable)';
        step(2.8, 'OME', `Buying partner custom rules · ${withCustom.length} mandate DMN table${withCustom.length > 1 ? 's' : ''} on ${csrc}`, 'POL',
          online.map((n, i) => (custom[i] ? `${n.id.replace('IAgent-', '')}: ${custom[i]!.error ? 'not checked' : custom[i]!.action?.toLowerCase()}${custom[i]!.message ? ` (${custom[i]!.message})` : ''}` : '')).filter(Boolean).join(' · '), '26a');
      }
      const bids: Bid[] = [];
      const arrivals = [5.2, 10.4, 12.3, 18.9, 45.1];
      online.forEach((n, i) => {
        const Y = Math.round((A * n.yieldAskPct) / 100);
        const c = custom[i];
        const customBlock = c && (c.error ? { code: 'CUSTOM_UNCHECKED', message: 'Custom rules could not be checked, so the agent does not bid (fails closed)' } : c.action === 'REJECT' ? { code: 'CUSTOM_REJECT', message: `Buying partner rule: ${c.message}` } : undefined);
        const blockHit = elig[i].hits.find((h) => h.severity === 'BLOCK') ?? customBlock;
        const b: Bid = {
          id: `BID-${5501 + Math.floor(Math.random() * 400)}`, nodeId: n.id, flow: n.flow, assetClass: n.assetClass, yieldAsk: Y, yieldAskPct: n.yieldAskPct,
          reliability: n.reliability, latencyMs: n.latencyMs, zkProof: n.flow === 'B' ? 'verified' : 'n/a', receivedAtMs: Math.min(n.latencyMs, arrivals[i % arrivals.length]), status: 'Received',
        };
        if (outcome === 'no-match') { b.status = 'Rejected'; b.rejectReason = `Yield floor above the buyer's max price (${inr(maxPrice)})`; }
        else if (blockHit?.code === 'PRICE_ABOVE_MAX') { b.status = 'Rejected'; b.rejectReason = `B = ${inr(A + Y + SCENARIO.fee)} exceeds max price ${inr(maxPrice)}`; }
        else if (blockHit) { b.status = 'Rejected'; b.rejectReason = blockHit.message.replace(/\.$/, ''); }
        const sc = scoreBid(b, cfg, dP);
        b.score = sc.score; b.scoreParts = sc.parts;
        bids.push(b);
      });
      for (const b of [...bids].sort((x, y) => x.receivedAtMs - y.receivedAtMs)) {
        if (!alive()) return;
        dispatch({ type: 'bid', id, bid: b });
        if (node(b.nodeId).isYou) dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: 'bid', text: `Bid submitted · ${req.auctionId} · yield ${inr(b.yieldAsk)}`, nodeId: b.nodeId } });
        await pace(380);
      }
      const ranked = rankBids(bids);
      step(cfg.batchWindowMs, 'OME', `SBBA window closed at ${cfg.batchWindowMs} ms · ${ranked.length} eligible bids`, 'POL', `${bids.length - ranked.length} rejected at intake`, '29');
      await pace(500);

      if (outcome === 'no-match' || ranked.length === 0) {
        step(cfg.intentTimeoutS * 1000, 'OME', `No match within the ${cfg.intentTimeoutS}s quote SLA`, 'FAIL', 'No hold was placed, so nothing to release', '30');
        dispatch({ type: 'patchRequest', id, patch: { status: 'Timed Out', failReason: 'No buying partner bid met your request before it expired. Nothing was charged: your hold is only placed after a match.' } });
        notify('Buyer', 'Request expired', 'No buying partner matched in time. Nothing was charged or held.', { tone: 'warn' });
        return;
      }

      // Provisional selection: winner + two hot backups.
      const order = ranked.slice(0, 3);
      const winner = order[0];
      const wNode = node(winner.nodeId);
      const split = splitOf(P, SCENARIO.discountPct, winner.yieldAsk, SCENARIO.fee);
      const statusFor = (b: Bid): Bid['status'] => (b.status === 'Rejected' ? 'Rejected' : b.id === winner.id ? 'Won' : order.some((o) => o.id === b.id) ? 'Backup' : 'Outbid');
      const finalBids = bids.map((b) => ({ ...b, status: statusFor(b), rank: order.findIndex((o) => o.id === b.id) + 1 || undefined }));
      dispatch({ type: 'bids', id, bids: finalBids });
      const backups = order.slice(1).map((b) => b.nodeId);
      step(50.2, 'OME', `Provisional winner ${wNode.label} · score ${winner.score?.toFixed(3)}`, 'POL',
        order.slice(1).map((b, i) => `#${i + 2} ${b.nodeId} ${b.score?.toFixed(3)}`).join(' · ') + ' · hot backups', '30');
      const pol = await makeProof('POL', 'Proof-of-Lock (match)', 'OME core + winning Buying Partner Agent (ECDSA)', {
        auction_id: req.auctionId, winner_inv_id: wNode.id, backup_ids: backups, sbba_score: winner.score, A: split.A, Y: split.Y, F: split.F, B: split.B, window_ms: cfg.batchWindowMs,
      }, poi);
      if (!alive()) return;
      dispatch({ type: 'proof', id, proof: pol });
      dispatch({ type: 'patchRequest', id, patch: { status: 'Matched', winnerNodeId: wNode.id, backupIds: backups, route: routeFor(wNode), quoteB: split.B, split } });
      reg('POL', `POL ${req.auctionId} · provisional winner ${wNode.id}`);
      step(51, 'OME', `Quote returned to ShopBot · B = ${inr(split.B)}`, 'POL', `A ${inr(split.A)} + Y ${inr(split.Y)} + F ${inr(split.F)}`, '31');

      // ---- Buyer confirmation (consumer rules for automated contracting) ----
      // The buyer chose a confirmation mode on first use. EU buyers always confirm each order, because DiscountAI
      // is paid a fee for proposing it; the confirmation fails closed when the time runs out.
      const prefs = ref.current.buyerPrefs;
      const reason = !prefs || skipConfirm ? null
        : prefs.region === 'EU' ? 'EU buyer: DiscountAI is paid a platform fee on this order, so you confirm every order'
          : prefs.mode === 'always' ? 'You chose to confirm every order'
            : prefs.mode === 'criteria' && (split.B > (prefs.maxPrice ?? Infinity) || Date.now() > Date.parse(prefs.untilIso ?? '9999-12-31')) ? `Outside your auto-confirm rule (up to ${inr(prefs.maxPrice ?? 0)}${prefs.untilIso ? ` until ${new Date(prefs.untilIso).toLocaleDateString('en-IN')}` : ''})`
              : null;
      if (reason) {
        const timeoutS = ref.current.speed === 'presenter' ? 120 : 60;
        const deadline = new Date(Date.now() + timeoutS * 1000).toISOString();
        dispatch({ type: 'patchRequest', id, patch: { status: 'Awaiting confirmation', confirmDeadline: deadline, confirmReason: reason } });
        step(52, 'Buyer Agent', `Waiting for your confirmation of ${inr(split.B)}`, 'POL', `${reason} · fee ${inr(split.F)} disclosed · expires in ${timeoutS} s (fails closed)`, '31a');
        notify('Buyer', `Confirm your order · ${inr(split.B)}`, `${SCENARIO.product} at ${SCENARIO.merchant}, shipped to you. DiscountAI is paid ${inr(split.F)} on this order. Confirm within ${timeoutS} seconds or the order is cancelled; nothing is held until you confirm.`, { tone: 'warn' });
        const yes = await new Promise<boolean>((resolve) => {
          pendingConfirm.current.set(id, resolve);
          setTimeout(() => { if (pendingConfirm.current.delete(id)) resolve(false); }, timeoutS * 1000);
        });
        if (!alive()) return;
        if (!yes) {
          step(52.5, 'Buyer Agent', 'Not confirmed: order cancelled', 'FAIL', 'Fails closed: no hold was placed and nothing was charged', '31a');
          dispatch({ type: 'patchRequest', id, patch: { status: 'Cancelled', failReason: 'You did not confirm the order in time (or declined it), so it was cancelled. Nothing was held or charged.' } });
          notify('Buyer', 'Order cancelled', 'The order was not confirmed, so it was cancelled. Nothing was held or charged.', { tone: 'info' });
          return;
        }
        step(53, 'Buyer Agent', `Confirmed by you · ${inr(split.B)}`, 'POL', 'Confirmation recorded with the order', '31a');
        dispatch({ type: 'patchRequest', id, patch: { status: 'Matched', confirmedAt: new Date().toISOString() } });
      }
      notify('Buyer', `Price found · ${inr(split.B)}`, `A buying partner's agent will buy ${SCENARIO.product} at ${SCENARIO.merchant} and ship it to you. You save ${inr(split.buyerSaves)}. Placing a hold on your card now.`, { tone: 'info' });
      for (const b of finalBids.filter((x) => node(x.nodeId).isYou && x.status !== 'Rejected')) {
        dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: b.status === 'Won' ? 'won' : 'outbid', text: b.status === 'Won' ? `Provisional win ${req.auctionId} · score ${b.score?.toFixed(3)}` : `${b.status === 'Backup' ? 'Backup' : 'Outbid'} on ${req.auctionId}`, nodeId: b.nodeId } });
      }
      await pace(800);

      // ---- Phase 5 · Buyer hold at Agent PA/PG + PRL ----
      const holdId = `chg_agpa_${hex(3)}`;
      step(320, 'Agent PA/PG', `Buyer auth-only hold B = ${inr(split.B)} at DiscountAI MID (MCC 5262)`, 'PRL', `POST /v1/charges · captureFlag false · ${holdId}`, '33–34');
      await pace(600);
      step(380, 'OME', `A = ${inr(split.A)} reserved in the in-flight exposure ledger`, 'PRL', 'L_rem = X − ΣSettled − ΣReserved', '35');
      const useOrder = `uo_${hex(3)}`;
      const prl = await makeProof('PRL', 'Proof-of-Reserve-Lock', 'OME + Agent PA/PG (ECDSA)', {
        buyer_hold_id: holdId, use_order_id: useOrder, amount_B: split.B, amount_A: split.A, inv_deposit_ref: `dep_${wNode.id.toLowerCase()}`, in_flight_rem: 50000 - split.A,
      }, pol);
      if (!alive()) return;
      dispatch({ type: 'proof', id, proof: prl });
      dispatch({ type: 'patchRequest', id, patch: { buyerHoldId: holdId } });
      reg('PRL', `PRL ${id} · buyer hold placed`);
      notify('Buyer', `Hold placed · ${inr(split.B)}`, `Your card has an authorisation hold of ${inr(split.B)}. It is only captured when the item ships.`, { tone: 'info' });
      await pace(700);

      // ---- Phase 6 · Purchase execution at Merchant M via Merchant PSP + silent failover (44b) ----
      // card-network agentic commerce: the buying partner's agent asks the card network for a credential under its purchase instruction,
      // with the matched buyer as the shipping address (server/vic.ts; simulated in this build).
      const [shipLine, shipCity] = SCENARIO.shipTo.split(',').map((s) => s.trim()).reduce<[string, string]>((acc, part, i, all) => i === all.length - 1 ? [acc[0], part] : [acc[0] ? `${acc[0]}, ${part}` : part, ''], ['', '']);
      const shipTo = { line1: shipLine, city: shipCity.replace(/\s*\d{6}$/, ''), state: 'TS', zip: (shipCity.match(/\d{6}/) ?? ['500032'])[0], countryCode: 'IN', name: 'DiscountAI matched buyer' };
      const vicRef: { current: { instructionId: string; txRef?: string } | null } = { current: null };
      const attempt = async (nd: InvestorNode, t0: number) => {
        step(t0, 'Buying Partner Agent', `UseOrder to ${nd.id} · executes under the buying partner's delegated instruction (no human present)`, 'POE', `Within budget · approved merchant · goods in scope · Purchaser: buying partner · Ship to: matched buyer (${SCENARIO.shipTo.split(',').slice(-1)[0].trim()})`, '36–36a');
        await pace(500);
        step(t0 + 300, 'Buying Partner Agent', `ACP checkout at ${SCENARIO.merchant} with TAP agenticConsumer header`, 'POE', `A = ${inr(split.A)} verified against the locked offer`, '37–39');
        await pace(500);
        const im = ref.current.mandates.find((x) => x.nodeId === nd.id && x.status === 'LIVE');
        // Buying partners seeded into the demo have no the card network instruction yet: register one (their delegated instruction) first.
        let instructionId = im?.vicInstructionId ?? vicInstructions.get(nd.id);
        if (!instructionId) {
          const ins = await vic('instruction.create', {
            budgetTotal: im?.budgetTotal ?? 40000, maxPerOrder: im?.maxPerTx ?? 25000, merchants: im?.merchants ?? [SCENARIO.merchant, 'Electronics Store B'],
            goods: im?.goods ?? [SCENARIO.category], validityDays: im?.validityDays ?? 7,
          }, 5000);
          instructionId = String(ins.response?.instructionId ?? '');
          if (ins.ok && instructionId) vicInstructions.set(nd.id, instructionId);
          if (im && instructionId) dispatch({ type: 'patchMandate', id: im.id, patch: { vicInstructionId: instructionId } });
        }
        const cred = await vic('credentials', { instructionId, amount: split.A, merchant: SCENARIO.merchant, shipTo }, 5000);
        if (!alive()) return false;
        vicRef.current = { instructionId, txRef: cred.response?.transactionReferenceId as string | undefined };
        const shortId = instructionId.length > 28 ? `${instructionId.slice(0, 24)}…` : instructionId;
        step(t0 + 600, 'Agent credential', `${cred.method ?? 'POST'} /vacp/v1/instructions/{id}/credentials · agent token (type 08 · AI Agent) + cryptogram for exactly ${inr(split.A)}`, 'POE',
          `${VIC_MODE_LABEL[cred.mode]} · ${cred.ok ? `the card network matched the order to instruction ${shortId} and released a single-use credential (masked)` : `the card network refused the credential: ${cred.error}`} · ships to the matched buyer · no card number reaches the buyer or ShopBot`, '40A');
        await pace(500);
        if (!cred.ok) return false;
        step(t0 + 900, 'Merchant PSP', 'ISO 0100 to Bank X · the card network F123 Tag 89 = 08 (AI Agent)', 'POE', 'On a Card-network rules card this would be DE 48 SE 48 SF 03 = 82 (Agent Pay, human not present)', '41A–43');
        await pace(600);
        return true;
      };
      const declined: string[] = [];
      const attempts: AttemptInput[] = [];
      let winnerInstruction: string | undefined;
      let finalNode: InvestorNode | null = null;
      let tCursor = 1000;
      for (let i = 0; i < order.length; i++) {
        const nd = node(order[i].nodeId);
        const credOk = await attempt(nd, tCursor);
        if (!alive()) return;
        const insId = vicRef.current?.instructionId;
        if (!credOk) {
          attempts.push({ investorId: nd.id, instructionId: insId, approved: false, reason: 'the card network refused the credential: order outside the instruction' });
          // the card network would not release a credential (order outside the buying partner's instruction): no charge, no slash; next bidder.
          declined.push(nd.id);
          step(tCursor + 1000, 'OME', `No credential for ${nd.id}: the order is outside that buying partner's the card network instruction`, 'FAIL', 'Nothing was charged; the buying partner is not penalised', '44b');
          if (i + 1 < order.length) step(tCursor + 1060, 'OME', `Silent failover to #${i + 2} ${order[i + 1].nodeId}`, 'POE', undefined, '44b.3');
          await pace(500);
          tCursor += 2000;
          continue;
        }
        const declines = outcome === 'all-decline' || (outcome === 'investor-decline' && i === 0);
        if (!declines) { finalNode = nd; winnerInstruction = insId; break; }
        attempts.push({ investorId: nd.id, instructionId: insId, approved: false, reason: 'ISO 0110 declined · 51 insufficient funds' });
        const slash = round((split.A * cfg.slashPct51) / 100);
        declined.push(nd.id);
        step(tCursor + 1500, 'Merchant PSP', `ISO 0110 declined · 51 insufficient funds (${nd.id})`, 'FAIL', undefined, '44');
        await pace(400);
        step(tCursor + 1520, 'Guarantee Fund', `${nd.id} deposit slashed ${inr(slash)} (${cfg.slashPct51}% of A) · reliability −5 pts`, 'FAIL', 'Buyer hold stays intact; buyer is not told', '44a');
        dispatch({ type: 'gf', delta: slash });
        dispatch({ type: 'node', id: nd.id, patch: { reliability: round(Math.max(0, nd.reliability - 0.05), 3), depositInr: Math.max(0, nd.depositInr - slash) } });
        if (nd.isYou) notify('Buying Partner', `Declined · ${inr(slash)} slashed`, `Your Bank X card declined (ISO 51) on ${req.auctionId}. ${inr(slash)} moved from your security deposit to the Guarantee Fund.`, { tone: 'warn' });
        if (i + 1 < order.length) step(tCursor + 1560, 'OME', `Silent failover to #${i + 2} ${order[i + 1].nodeId}`, 'POE', undefined, '44b.3');
        await pace(500);
        tCursor += 2000;
      }

      let route: SettlementRoute = routeFor(finalNode ?? wNode);
      if (!finalNode) {
        // All bidders failed: OME buys at list with its corporate card; the Guarantee Fund covers P − B.
        route = 'Guarantee Fund backstop';
        step(tCursor, 'OME', `All bidders declined · OME corporate card buys at list ${inr(P)}`, 'POE', `Buyer still pays the quoted ${inr(split.B)}`, '44b.4');
        dispatch({ type: 'gf', delta: -(P - split.B) });
        step(tCursor + 100, 'Guarantee Fund', `Covers P − B = ${inr(P - split.B)}`, 'POE', undefined, '44b.5');
        await pace(600);
      }
      const authCode = `${hex(3).toUpperCase()}`;
      const orderId = `CRM-${90000 + Math.floor(Math.random() * 9999)}`;
      if (finalNode) attempts.push({ investorId: finalNode.id, instructionId: winnerInstruction, approved: true, authCode });
      step(tCursor + 1500, 'Merchant PSP', `ISO 0110 approved · ${inr(finalNode ? split.A : P)} · order ${orderId}`, 'POE', `Auth ${authCode}`, '44');
      await pace(400);
      step(tCursor + 1560, 'OME', 'COMMIT · order.confirmed sent to ShopBot', 'POE', 'Buyer is told only after the merchant approves (two-phase commit)', '46–47');
      dispatch({ type: 'patchRequest', id, patch: { status: 'Confirmed', winnerNodeId: (finalNode ?? wNode).id, failedNodeIds: declined, route, orderId } });
      notify('Buyer', 'Order confirmed', `${SCENARIO.merchant} confirmed order ${orderId}. It ships to your address; your ${inr(split.B)} hold is captured on shipment.`, { tone: 'good' });
      if (finalNode?.isYou) notify('Buying Partner', `Purchase made · ${inr(split.A)} on your Bank X card`, `Your agent bought ${SCENARIO.product} at ${SCENARIO.merchant} for a matched buyer (bill: you, ship: buyer). Payout of ${inr(split.payout)} follows shipment, before your card due date.`, { tone: 'info' });
      await pace(900);

      // ---- Phase 7–8 · Shipment, capture, payout ----
      const tracking = `BLUEDART-${70000 + Math.floor(Math.random() * 9999)}`;
      step(tCursor + 5000, 'Merchant', `order.shipped webhook · ${tracking}`, 'POE', `${SCENARIO.merchant} + Merchant PSP clear ${inr(split.A)}`, '48–49');
      if (finalNode && vicRef.current) {
        const conf = await vic('confirm', { instructionId: vicRef.current.instructionId, transactionReferenceId: vicRef.current.txRef, amount: split.A, merchant: SCENARIO.merchant, orderId, tracking, shipTo }, 5000);
        if (!alive()) return;
        step(tCursor + 5100, 'Agent credential', `${conf.method ?? 'POST'} /vacp/v1/instructions/{id}/confirmations · order and shipment confirmed to the card network`, 'POE', `${VIC_MODE_LABEL[conf.mode]} · order ${orderId} · tracking ${tracking}${conf.ok ? '' : ` · ${conf.error}`}`, '49');
      }
      const poe = await makeProof('POE', 'Proof-of-Execution', 'Buying Partner Agent + Merchant PSP webhook (HMAC)', {
        vic_instruction_id: vicRef.current?.instructionId ?? null, mc_intent_id: null, iso_auth_code: authCode, A_captured: finalNode ? split.A : P, merchant_order_id: orderId, tracking_id: tracking,
        purchaser_of_record: finalNode ? finalNode.id : 'OME corporate card', ship_to_hash: shipHash,
      }, prl);
      if (!alive()) return;
      dispatch({ type: 'proof', id, proof: poe });
      dispatch({ type: 'patchRequest', id, patch: { trackingId: tracking } });
      reg('POE', `POE ${orderId} shipped`);
      notify('Merchant', `Order ${orderId} shipped`, `Sold to ${finalNode ? `buying partner ${finalNode.id}` : 'DiscountAI (corporate card)'} at ${inr(finalNode ? split.A : P)}; shipped to the buyer.`, { tone: 'info' });
      await pace(700);
      step(tCursor + 5200, 'Agent PA/PG', `Buyer hold captured · ${inr(split.B)} into PA escrow`, 'POC', 'POST /v1/captures', '50');
      await pace(500);
      const gfShare = split.guaranteeFund;
      // A backup bidder is paid its own yield ask; the buyer keeps the quoted B and the Guarantee Fund tops up any gap.
      const finalY = finalNode ? (finalBids.find((b) => b.nodeId === finalNode!.id)?.yieldAsk ?? split.Y) : 0;
      const topUp = Math.max(0, finalY - split.Y);
      const payout = split.A + finalY;
      if (finalNode && topUp > 0) {
        step(tCursor + 5250, 'Guarantee Fund', `Tops up ${inr(topUp)}: backup asked ${inr(finalY)}, buyer still pays the quoted ${inr(split.B)}`, 'POC', undefined, '44b');
        dispatch({ type: 'gf', delta: -topUp });
      }
      // Risk-tiered split release: yield waits for the merchant's return window; principal goes to the card bill.
      const returnDays = 7;
      const releaseAt = new Date(Date.now() + (4 + returnDays) * 864e5).toISOString(); // delivery (~4 days) + return window
      const tier1 = !!finalNode && finalNode.reliability >= 0.98;
      if (finalNode) {
        if (tier1) {
          step(tCursor + 5300, 'OME', `Principal ${inr(split.A)} paid to ${finalNode.id}'s Bank X card bill before the due date`, 'POC', 'Tier 1 (reliability ≥ 98%): paid to the card bill via BBPS, not as cash · backed by the 5% deposit and rolling reserve', '52–53');
          step(tCursor + 5320, 'OME', `Yield ${inr(finalY)} held in escrow until the return window closes`, 'POC', `Releases ${new Date(releaseAt).toLocaleDateString('en-IN')} (delivery + ${returnDays}-day return window) · fee ${inr(split.F)} retained · ${inr(gfShare)} to the Guarantee Fund`, '52a');
        } else {
          step(tCursor + 5300, 'OME', `Payout ${inr(payout)} (A + Y) held in escrow until the return window closes`, 'POC', `Tier 0 (new or unproven buying partner): releases ${new Date(releaseAt).toLocaleDateString('en-IN')} to the card bill · fee ${inr(split.F)} retained · ${inr(gfShare)} to the Guarantee Fund`, '52a');
        }
        dispatch({ type: 'patchRequest', id, patch: { payoutPlan: { tier: tier1 ? 1 : 0, principal: split.A, yield: finalY, principalTo: 'Bank X card bill (BBPS)', principalPaidAt: tier1 ? new Date().toISOString() : undefined, releaseAt } } });
      } else {
        step(tCursor + 5300, 'OME', `Escrow ${inr(split.B)} reimburses the corporate card; Guarantee Fund absorbed ${inr(P - split.B)}`, 'POC', undefined, '44b.5');
      }
      dispatch({ type: 'gf', delta: gfShare });
      const poc = await makeProof('POC', 'Proof-of-Compliance', 'OME Compliance Engine (ECDSA)', {
        escrow_capture_id: `cap_agpa_${hex(3)}`, payout_id: finalNode ? `po_${hex(3)}` : null, payout_A_plus_Y: finalNode ? payout : 0, fee_F: split.F,
        guarantee_fund_alloc: gfShare, guarantee_fund_topup: topUp, refund_sync_ref: null, iors_ack_ts: new Date().toISOString(),
      }, poe);
      if (!alive()) return;
      dispatch({ type: 'proof', id, proof: poc });
      reg('POC', `POC ${id} · settlement streamed to IoRS`);
      await pace(400);
      const checks = await verifyChain([poi, pol, prl, poe, poc]);
      const valid = checks.every((c) => c.hashOk && c.linkOk && c.sigOk);
      step(tCursor + 5500, 'Compliance', `Chain verified POI → POL → PRL → POE → POC ${valid ? '✓' : '✗'}`, 'POC', undefined, '54–56');

      // Per-payment evidence chains (S mandate setup, B buyer sale, I per buying partner attempt) + reconciliation record.
      const winM = finalNode ? ref.current.mandates.find((x) => x.nodeId === finalNode!.id && x.status === 'LIVE') : undefined;
      const setup = winM?.setupChain ?? await buildSetupChain({
        mandateId: winM?.id ?? `M-${(finalNode ?? wNode).id}`, investorId: (finalNode ?? wNode).id, budgetTotal: winM?.budgetTotal ?? 40000, maxPerOrder: winM?.maxPerTx ?? 25000,
        merchants: winM?.merchants ?? [SCENARIO.merchant, 'Electronics Store B'], goods: winM?.goods ?? [SCENARIO.category], validityDays: winM?.validityDays ?? 7,
        instructionId: winnerInstruction, customRules: winM?.customRules?.rules.length,
      });
      const evidenceInput: OrderInput = {
        orderId: id, product: SCENARIO.product, merchant: SCENARIO.merchant, maxPrice, shipHash,
        dfaMode: prefsMode(ref.current.buyerPrefs, skipConfirm), B: split.B, A: split.A, Y: finalY, F: split.F, holdId, attempts, tracking, setup,
      };
      const evidence = await buildOrderEvidence(evidenceInput);
      if (!alive()) return;
      step(tCursor + 5600, 'Compliance', `Evidence sealed: Chain S, Chain B, ${evidence.I.length} × Chain I${evidence.recon ? ' · reconciliation B = A + Y + F' : ''}`, 'POC',
        `Each chain: authorisation = cart total (AEP MUST-1) · operator seal + witnesses (${evidence.B.seal?.operator.alg})`, '54–56');
      dispatch({ type: 'patchRequest', id, patch: { status: 'Success', chainValid: valid, payoutDone: !!finalNode, investorYieldPaid: finalNode ? finalY : 0, evidence, evidenceInput } });

      const m = finalNode ? ref.current.mandates.find((x) => x.nodeId === finalNode!.id && x.flow === finalNode!.flow && x.status === 'LIVE') : undefined;
      if (finalNode?.isYou) {
        dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: 'settled', text: `Paid out ${req.auctionId} · ${SCENARIO.product}`, amount: split.Y, nodeId: finalNode.id } });
        if (m) dispatch({ type: 'patchMandate', id: m.id, patch: { yieldEarned: round(m.yieldEarned + finalY), utilised: m.utilised + split.A } });
      }
      notify('Buyer', `Shipped · you saved ${inr(split.buyerSaves)}`, `${SCENARIO.product} is on its way (${tracking}). ${inr(split.B)} captured from your hold.`, { tone: 'good', amount: -split.B });
      if (finalNode) notify('Buying Partner', finalNode.isYou ? `Payout ${inr(payout)} · yield +${inr(finalY)}` : `Payout to ${finalNode.owner}`,
        `${inr(split.A)} principal reimbursed plus ${inr(finalY)} yield, before your Bank X statement is due${m && finalNode.isYou ? ` · mandate ${m.id}` : ''}.`, { tone: 'good', amount: finalY });
      notify('Platform', `Platform fee +${inr(split.F)}`, `${inr(gfShare)} of it to the Guarantee Fund.${declined.length ? ` Slashes collected from ${declined.join(', ')}.` : ''}`, { tone: 'good', amount: split.F });
      notify('Regulator', `POC filed · chain ${valid ? 'verified' : 'BROKEN'}`, `IoRS report for ${id}: B ${inr(split.B)}, A ${inr(split.A)}, Y ${inr(split.Y)}, F ${inr(split.F)}. Five proofs streamed within 500 ms.`, { tone: valid ? 'info' : 'warn' });
      if (finalNode) dispatch({ type: 'node', id: finalNode.id, patch: { reliability: round(Math.min(1, finalNode.reliability * 0.97 + 0.03), 3) } });
    })();
    return id;
  }, [pace]);

  // Two-leg refunds
  const refundRequest = useCallback<Ctx['refundRequest']>((id, kind) => {
    const r = ref.current.requests.find((x) => x.id === id);
    if (!r?.split || r.status !== 'Success') return;
    // Claw back what was actually paid out (a failover backup may have earned a different yield than the quote).
    const rec: RefundRecord = { at: new Date().toISOString(), ...refundFor({ ...r.split, Y: r.investorYieldPaid ?? r.split.Y }, kind) };
    const t = (r.timeline.at(-1)?.t ?? 0) + 1000;
    const add = (dt: number, actor: TimelineStep['actor'], label: string, detail: string | undefined, stepNo: string) =>
      dispatch({ type: 'step', id, step: { t: t + dt, actor, label, detail, phase: 'REFUND', step: stepNo } });
    const label = kind === 'partial' ? 'Partial return (50%)' : kind === 'post-payout' ? 'Return after buying partner payout' : 'Return before buying partner payout';
    add(0, 'Buyer', `${label} · RMA relayed to ${r.merchant}`, undefined, '57');
    add(200, 'Merchant PSP', `Leg 1 · ${r.merchant} refunds ${inr(rec.merchantRefundToInvestor)} to the buying partner's Bank X card`, 'MTI 0200 DE3 = 20', '57a');
    add(250, 'Agent PA/PG', `Leg 2 · ${inr(rec.buyerRefund)} refunded to the buyer's original card/UPI`, 'POST /v1/refunds · immediate', '57b');
    if (rec.investorClawback) add(400, 'OME', `Recover ${inr(rec.investorClawback)} from the buying partner`, rec.recovery, '57c');
    else add(400, 'OME', 'Buying partner payout frozen', rec.recovery, '57a–b');
    dispatch({ type: 'refund', id, refund: rec });
    dispatch({ type: 'patchRequest', id, patch: { status: kind === 'partial' ? 'Success' : 'Refunded' } });
    // Refund legs are post-seal events in the evidence chains: leg 2 in Chain B, leg 1 in the converting Chain I.
    if (r.evidenceInput) {
      buildOrderEvidence({ ...r.evidenceInput, refund: { buyerRefund: rec.buyerRefund, merchantRefundToInvestor: rec.merchantRefundToInvestor } })
        .then((evidence) => dispatch({ type: 'patchRequest', id, patch: { evidence } }));
    }
    dispatch({ type: 'regulator', ev: { at: new Date().toISOString(), proof: 'POC', requestId: id, latencyMs: 120, text: `refund_sync_ref for ${id} · ${kind}` } });
    dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: 'refund', text: `${label} · ${r.product}`, amount: -rec.investorClawback, nodeId: r.winnerNodeId } });
    const n = (party: Notice['party'], title: string, body: string, amount?: number, tone: Notice['tone'] = 'info') =>
      dispatch({ type: 'notify', notice: { id: rid('n'), at: new Date().toISOString(), party, title, body, amount, tone, requestId: id } });
    n('Buyer', `Refunded ${inr(rec.buyerRefund)}`, `Back to your original payment method straight away; you don't wait for the card refund between ${r.merchant} and the buying partner.`, rec.buyerRefund, 'good');
    n('Buying Partner', `${r.merchant} refunded ${inr(rec.merchantRefundToInvestor)} to your card`, rec.investorClawback ? `${inr(rec.investorClawback)} will be recovered: ${rec.recovery}.` : rec.recovery, rec.investorClawback ? -rec.investorClawback : undefined, 'warn');
    n('Platform', `Fee reversed −${inr(rec.feeReversal)}`, `Two-leg refund for ${id} (${kind}).`, -rec.feeReversal, 'warn');
    const you = ref.current.nodes.find((x) => x.isYou);
    const m = you && r.winnerNodeId === you.id ? ref.current.mandates.find((x) => x.nodeId === you.id && x.status === 'LIVE') : undefined;
    if (m && rec.investorClawback) dispatch({ type: 'patchMandate', id: m.id, patch: { yieldEarned: round(m.yieldEarned - (rec.investorClawback - rec.merchantRefundToInvestor)) } });
  }, []);

  const setConfig = useCallback((config: AuctionConfig, change: string) => {
    dispatch({ type: 'config', config, entry: { at: new Date().toISOString(), who: 'ops.admin (you)', change } });
    dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: 'config', text: 'Auction config changed · applies next batch' } });
  }, []);
  const addMandate = useCallback((m: Mandate) => {
    const you = ref.current.nodes.find((n) => n.isYou)!;
    const deposit = m.deposit ?? Math.round((m.limit * ref.current.config.depositPct) / 100);
    if (LIVE) {
      // Register on the exchange; once accepted it bids automatically (a standing order) within its rules.
      exchange.registerMandate({ partnerRef: you.id, limitMinor: m.limit * 100, maxPerOrderMinor: m.maxPerTx * 100, minYieldPct: m.minYieldPct,
        depositMinor: deposit * 100, budgetTotalMinor: (m.budgetTotal ?? m.limit) * 100, merchantCount: Math.max(1, m.merchants?.length ?? 1),
        goodsCount: Math.max(1, m.goods?.length ?? 1), validityDays: m.validityDays ?? 7, cardJurisdiction: 'IN_DOMESTIC_AFA' })
        .then((r) => dispatch({ type: 'patchMandate', id: m.id, patch: { exchangeMandateId: r.mandateId, status: 'LIVE' } }))
        .catch((e) => {
          dispatch({ type: 'patchMandate', id: m.id, patch: { status: 'Paused' } });
          dispatch({ type: 'notify', notice: { id: rid('n'), at: new Date().toISOString(), party: 'Buying Partner', title: 'Mandate not accepted',
            body: e instanceof ExchangeError ? (e.status === 0 ? 'The exchange is unreachable.' : e.message) : String(e) } });
        });
    }
    const mandate: Mandate = { ...m, nodeId: you.id, deposit, intentType: m.flow === 'A' ? 'Delegated budget · no human present' : m.intentType, vppRefreshDue: m.vppRefreshDue ?? new Date(Date.now() + (m.validityDays ?? 7) * 864e5).toISOString() };
    dispatch({ type: 'mandate', mandate });
    // Chain S: the mandate setup (intent + passkey delegation), sealed when the the card network instruction is registered.
    buildSetupChain({ mandateId: m.id, investorId: you.id, budgetTotal: m.budgetTotal ?? m.limit, maxPerOrder: m.maxPerTx, merchants: m.merchants ?? [], goods: m.goods ?? [], validityDays: m.validityDays ?? 7, instructionId: m.vicInstructionId, customRules: m.customRules?.rules.length })
      .then((setupChain) => dispatch({ type: 'patchMandate', id: m.id, patch: { setupChain } }));
    // A Bank X card mandate brings the buying partner's card node online.
    if (m.flow === 'A') dispatch({ type: 'node', id: you.id, patch: { online: true, mandateId: m.id, activeMandates: you.activeMandates + 1, depositInr: deposit, vppAgeDays: 0 } });
    dispatch({ type: 'feed', item: { id: rid('f'), at: new Date().toISOString(), kind: 'mandate', text: `Mandate ${m.id} ACTIVE · deposit ${inr(deposit)} in escrow`, nodeId: you.id } });
    dispatch({ type: 'notify', notice: { id: rid('n'), at: new Date().toISOString(), party: 'Buying Partner', title: `Mandate ${m.id} is ACTIVE`, body: `${m.instrument} is live in the order book. Security deposit ${inr(deposit)} held in Agent PA/PG escrow; your agent buys for you, with no human in the loop, up to ${inr(m.maxPerTx)} per order${m.budgetTotal ? ` and ${inr(m.budgetTotal)} in total` : ''} at yield ≥ ${m.minYieldPct}%, shipping each item to the matched buyer. The instruction lasts ${m.validityDays ?? 7} days; then you approve a new one with your passkey.`, tone: 'good' } });
  }, []);
  const patchMandate = useCallback((id: string, patch: Partial<Mandate>) => dispatch({ type: 'patchMandate', id, patch }), []);
  const setSpeed = useCallback((speed: Speed) => dispatch({ type: 'speed', speed }), []);
  const reset = useCallback(() => { runId.current++; dispatch({ type: 'reset', state: seedState() }); }, []);

  const setBuyerPrefs = useCallback((prefs: BuyerPrefs) => {
    dispatch({ type: 'buyerPrefs', prefs });
    dispatch({ type: 'notify', notice: { id: rid('n'), at: new Date().toISOString(), party: 'Buyer', tone: 'info',
      title: 'Your confirmation setting', body: `Saved on ${new Date(prefs.chosenAt).toLocaleString('en-IN')}: ${prefs.mode === 'always' ? 'confirm every order' : prefs.mode === 'criteria' ? `auto-confirm orders up to ${inr(prefs.maxPrice ?? 0)}${prefs.untilIso ? ` until ${new Date(prefs.untilIso).toLocaleDateString('en-IN')}` : ''}, confirm the rest` : 'no confirmation needed'}${prefs.region === 'EU' ? '. As an EU buyer you still confirm every order, because DiscountAI is paid a fee on it' : ''}. You can change this any time in ShopBot. In the live service this confirmation is also e-mailed to you.` } });
  }, []);
  const confirmQuote = useCallback((id: string, yes: boolean) => {
    const resolve = pendingConfirm.current.get(id);
    if (resolve) { pendingConfirm.current.delete(id); resolve(yes); }
  }, []);
  const value = useMemo(() => ({ state, submitRequest, refundRequest, setConfig, addMandate, patchMandate, setSpeed, reset, setBuyerPrefs, confirmQuote }),
    [state, submitRequest, refundRequest, setConfig, addMandate, patchMandate, setSpeed, reset, setBuyerPrefs, confirmQuote]);
  return <EngineCtx.Provider value={value}>{children}</EngineCtx.Provider>;
}

// Full chain for a completed seeded request.
async function chainFor(r: BuyRequest, score: number): Promise<ProofObject[]> {
  const s = r.split!;
  const shipHash = (await sha256(SCENARIO.shipTo)).slice(0, 16);
  const poi = await makeProof('POI', 'Proof-of-Intent', 'Buyer Agent (ECDSA) + TAP signature', { intent_id: r.id, product_id: 'prod-014', product: r.product, merchants: ['M1'], max_price: r.maxPrice, currency: 'INR', ship_to_hash: shipHash, deadline: '30s', buyer_agent: 'shopbot.agent/7f21' });
  const pol = await makeProof('POL', 'Proof-of-Lock (match)', 'OME core + winning Buying Partner Agent (ECDSA)', { auction_id: r.auctionId, winner_inv_id: r.winnerNodeId, backup_ids: r.backupIds ?? [], sbba_score: score, A: s.A, Y: s.Y, F: s.F, B: s.B, window_ms: 50 }, poi);
  const prl = await makeProof('PRL', 'Proof-of-Reserve-Lock', 'OME + Agent PA/PG (ECDSA)', { buyer_hold_id: r.buyerHoldId, use_order_id: `uo_${hex(3)}`, amount_B: s.B, amount_A: s.A, inv_deposit_ref: 'dep_iagent-c', in_flight_rem: 50000 - s.A }, pol);
  const poe = await makeProof('POE', 'Proof-of-Execution', 'Buying Partner Agent + Merchant PSP webhook (HMAC)', { vic_instruction_id: `vic_${hex(4)}`, mc_intent_id: null, iso_auth_code: hex(3).toUpperCase(), A_captured: s.A, merchant_order_id: r.orderId, tracking_id: r.trackingId, purchaser_of_record: r.winnerNodeId, ship_to_hash: shipHash }, prl);
  const poc = await makeProof('POC', 'Proof-of-Compliance', 'OME Compliance Engine (ECDSA)', { escrow_capture_id: `cap_agpa_${hex(3)}`, payout_id: `po_${hex(3)}`, payout_A_plus_Y: s.payout, fee_F: s.F, guarantee_fund_alloc: s.guaranteeFund, refund_sync_ref: null, iors_ack_ts: r.createdAt }, poe);
  return [poi, pol, prl, poe, poc];
}
