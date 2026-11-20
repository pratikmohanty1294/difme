// FR-A1.1 — a small, transparent rule-based parser standing in for the Capital AI agent's
// natural-language understanding of a delegation instruction.

export const MCC_CATEGORIES = ['Electronics', 'Travel', 'Dining', 'Grocery', 'Gaming', 'Gambling'];

export interface Delegation {
  risk_tolerance: string;
  yield_target: number;
  asset_preferences: string;
  mcc_exclusions: string[];
  max_per_transaction: number;
}

export function parseDelegation(text: string): Delegation {
  const t = text.toLowerCase();
  const money = t.match(/(?:<|under|below|up to|max(?:imum)?)\s*(?:\$|₹|rs\.?)?\s*([\d,.]+)\s*(k)?/);
  const max = money ? Number(money[1].replace(/,/g, '')) * (money[2] ? 1000 : 1) : 10000;
  const y = t.match(/yield\s*(?:is\s*)?(?:>|above|over|at least|>=)?\s*([\d.]+)\s*%/);
  const excl = MCC_CATEGORIES.filter((c) => new RegExp(`(no|exclude|excluding|avoid|not)\\s+[a-z ,]*${c.toLowerCase()}`).test(t));
  const asset = /usdc|stablecoin|crypto/.test(t) ? 'STABLECOIN' : /upi/.test(t) ? 'UPI_VAULT' : /rwa|reit|property/.test(t) ? 'TOKENIZED_RWA' : 'FIAT_CREDIT';
  const risk = /conservative|safe|low risk/.test(t) ? 'low' : /aggressive|max(imum)? yield/.test(t) ? 'high' : 'balanced';
  return { risk_tolerance: risk, yield_target: y ? Number(y[1]) : 5, asset_preferences: asset, mcc_exclusions: excl.length ? excl : ['Gaming', 'Gambling'], max_per_transaction: max };
}
