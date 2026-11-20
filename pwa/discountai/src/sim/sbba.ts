import type { AuctionConfig, Bid } from './types';

// v4.1 §3.2 SD-C1: Score_i = w1 · U_i + w2 · (R_i / 100) − w3 · (L_i / L_max)
// U_i is the buyer-surplus utility of the yield ask: U = (d·P − Y_i) / (d·P), so a lower yield ask scores higher.
// Worked example (A = ₹8,000, d·P = ₹2,000, L_max = 50 ms, Flow A weights 0.50 / 0.40 / 0.10):
//   A ₹640, 60 %, 10 ms → 0.560 · B ₹600, 99 %, 5 ms → 0.736 (winner) · C ₹560, 90 %, 45 ms → 0.630
export function scoreBid(bid: Bid, cfg: AuctionConfig, discountAmount: number) {
  const w = cfg.weights[bid.flow];
  const utility = discountAmount > 0 ? Math.max(0, (discountAmount - bid.yieldAsk) / discountAmount) : 0;
  const utilPart = w.w1 * utility;
  const relPart = w.w2 * bid.reliability;
  const latPart = w.w3 * Math.min(1, bid.latencyMs / cfg.latencyMaxMs);
  return {
    score: round(utilPart + relPart - latPart, 3),
    parts: { utility: round(utility, 3), utilPart: round(utilPart, 3), relPart: round(relPart, 3), latPart: round(latPart, 3), w },
  };
}

// Highest score wins; ties go to the lower yield ask, then the earlier bid. Ranks 2 and 3 are hot backups.
export function rankBids(bids: Bid[]) {
  return [...bids]
    .filter((b) => b.status !== 'Rejected')
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0) || a.yieldAsk - b.yieldAsk || a.receivedAtMs - b.receivedAtMs);
}

export const round = (n: number, d = 2) => Math.round(n * 10 ** d) / 10 ** d;
