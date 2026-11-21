import { LIVE, exchange, ExchangeError } from '../api/exchange';
import { compileDmn, customDecisionId } from './dmn-compile';
// Rule engine client for intent creation. The rules live in rules/*.dmn and run server-side in our /api/rules
// edge function (DMN tables evaluated with FEEL; an external Fluxnova engine can be plugged in there).
// If the function can't be reached, the same rules run here locally so the demo never blocks; every answer
// says which one decided.

export type Decision = 'mandate_validation' | 'buyer_intent_eligibility' | 'investor_eligibility';
export type Severity = 'BLOCK' | 'WARN' | 'INFO';
export interface RuleHit { code: string; severity: Severity; message: string }
export interface RuleResult {
  decision: Decision;
  source: 'edge' | 'fluxnova' | 'local';
  ok: boolean;              // no BLOCK hits
  hits: RuleHit[];
  ms: number;
  note?: string;            // why the local fallback was used
}
type Vars = Record<string, number | string | boolean>;

/* ---- local mirror of rules/*.dmn (keep in step with the DMN files) ---- */
const LOCAL: Record<Decision, (v: Vars) => RuleHit[]> = {
  mandate_validation: (v) => {
    const n = (k: string) => Number(v[k]);
    const h: RuleHit[] = [];
    if (n('maxPerOrder') > n('limit')) h.push({ code: 'MAX_ABOVE_LIMIT', severity: 'BLOCK', message: 'Max per order is above the mandate limit.' });
    if (n('maxPerOrder') > 25000) h.push({ code: 'MAX_ABOVE_ORDER_CAP', severity: 'BLOCK', message: 'Max per order is above the ₹25,000 per-order cap (DiscountAI policy).' });
    if (n('minYieldPct') < 2) h.push({ code: 'YIELD_FLOOR_TOO_LOW', severity: 'BLOCK', message: "Minimum yield below 2% isn't allowed." });
    if (n('minYieldPct') > 15) h.push({ code: 'YIELD_FLOOR_HIGH', severity: 'WARN', message: 'A minimum yield above 15% will rarely win an auction.' });
    // depositRequired = the exchange's deposit rate × the total budget, worked out by the caller.
    if (n('deposit') < n('depositRequired')) h.push({ code: 'DEPOSIT_SHORT', severity: 'BLOCK', message: "Security deposit is below what the exchange requires: 5 % of your budget, or the collateral on your largest order at your tier, whichever is higher." });
    // Delegated purchase intent: budget, approved merchants, goods and a validity period (card-network agent pay; Agent credential instruction).
    if (n('budgetTotal') <= 0) h.push({ code: 'NO_BUDGET', severity: 'BLOCK', message: 'Set a total budget. Card-network rules requires a budget before an agent may buy without you present.' });
    if (n('budgetTotal') > n('limit')) h.push({ code: 'BUDGET_ABOVE_LIMIT', severity: 'BLOCK', message: 'Total budget is above your mandate limit.' });
    if (n('maxPerOrder') > n('budgetTotal')) h.push({ code: 'MAX_ABOVE_BUDGET', severity: 'BLOCK', message: 'Max per order is above the total budget.' });
    if (n('merchantCount') < 1) h.push({ code: 'NO_MERCHANTS', severity: 'BLOCK', message: 'Pick at least one approved merchant. Card-network rules requires approved merchants for purchases without you present.' });
    if (n('goodsCount') < 1) h.push({ code: 'NO_GOODS', severity: 'BLOCK', message: 'Pick what the agent may buy. Card-network rules requires a description of the goods.' });
    if (n('validityDays') < 1) h.push({ code: 'NO_VALIDITY', severity: 'BLOCK', message: 'Set how long the instruction is valid. Card-network rules requires a validity period.' });
    if (n('validityDays') > 7) h.push({ code: 'VALIDITY_TOO_LONG', severity: 'BLOCK', message: 'An instruction can last at most 7 days, then you confirm a new one with your passkey (DiscountAI policy: the card network asks for fresh authentication on older instructions).' });
    if (v.cardJurisdiction === 'IN_DOMESTIC_AFA' || v.cardJurisdiction === 'US_GLOBAL') h.push({ code: 'DELEGATED_NO_HUMAN', severity: 'INFO', message: 'Your agent buys without asking you each time, only within this budget, these merchants and these goods, until the instruction expires. You are the purchaser; items ship to the matched buyer.' });
    if (v.cardJurisdiction === 'IN_DOMESTIC_AFA') h.push({ code: 'INDIA_PREAUTH_MANDATE', severity: 'INFO', message: 'India-issued card: approved once as a pre-authorised card mandate, with the additional factor of authentication at setup. Purchases under it need no AFA.' });
    return h;
  },
  buyer_intent_eligibility: (v) => {
    const n = (k: string) => Number(v[k]);
    const h: RuleHit[] = [];
    if (n('riskScore') < (v.minRiskScore === undefined ? 850 : n('minRiskScore'))) h.push({ code: 'RISK_TOO_LOW', severity: 'BLOCK', message: "L3 risk score is below the exchange's minimum, so the request can't be broadcast." });
    if (n('maxPrice') < n('minViable')) h.push({ code: 'MAX_PRICE_TOO_LOW', severity: 'BLOCK', message: 'Max price is below the card price plus the platform fee, so no buying partner can match.' });
    if (n('maxPrice') > n('listPrice')) h.push({ code: 'ABOVE_LIST_PRICE', severity: 'WARN', message: 'Max price is above the list price; buying direct would be cheaper.' });
    if (v.offerSanctioned === false) h.push({ code: 'OFFER_NOT_SANCTIONED', severity: 'BLOCK', message: "This Bank X offer isn't sanctioned for third-party purchase (V4, restricted)." });
    if (n('riskScore') >= 850 && n('maxPrice') >= n('minViable') && v.offerSanctioned === true) h.push({ code: 'ELIGIBLE', severity: 'INFO', message: 'Intent is eligible for the exchange.' });
    return h;
  },
  investor_eligibility: (v) => {
    const n = (k: string) => Number(v[k]);
    const h: RuleHit[] = [];
    if (v.flow !== 'A') return [{ code: 'FLOW_NOT_CARD', severity: 'BLOCK', message: 'Offer needs a Bank X card at the merchant; non-card flows are not eligible.' }];
    if (n('intentAgeDays') >= n('validityDays')) h.push({ code: 'INTENT_EXPIRED', severity: 'BLOCK', message: "The buying partner's instruction has expired; they must confirm a new one with their passkey." });
    if (n('deposit') < n('maxDepositNeeded')) h.push({ code: 'DEPOSIT_SHORT', severity: 'BLOCK', message: 'Security deposit not posted.' });
    if (n('amountA') > n('maxPerOrder')) h.push({ code: 'ABOVE_MAX_PER_ORDER', severity: 'BLOCK', message: "Order amount is above the buying partner's max per order." });
    if (n('budgetLeft') < n('amountA')) h.push({ code: 'BUDGET_EXHAUSTED', severity: 'BLOCK', message: "Order is larger than the buying partner's remaining budget." });
    if (v.merchantApproved === false) h.push({ code: 'MERCHANT_NOT_APPROVED', severity: 'BLOCK', message: "This merchant isn't on the buying partner's approved list." });
    if (v.goodsInScope === false) h.push({ code: 'GOODS_OUT_OF_SCOPE', severity: 'BLOCK', message: 'This product is outside the goods the buying partner approved.' });
    if (n('yieldAsk') > n('yieldRoom')) h.push({ code: 'PRICE_ABOVE_MAX', severity: 'BLOCK', message: "This yield would push B above the buyer's max price." });
    return h;
  },
};

export function evaluateLocal(decision: Decision, vars: Vars, note?: string): RuleResult {
  const t = performance.now();
  const hits = LOCAL[decision](vars);
  return { decision, source: 'local', ok: !hits.some((h) => h.severity === 'BLOCK'), hits, ms: Math.round(performance.now() - t), note };
}

export interface CustomResult {
  ok: boolean;                 // engine answered and no REJECT
  source: 'edge' | 'fluxnova' | 'exchange' | 'unavailable';
  engine?: string;
  action?: 'APPROVE' | 'REJECT' | 'FLAG';
  message?: string;
  dmn?: string;                // compiled DMN 1.3 XML, when requested
  error?: string;              // invalid rule, or engine unreachable
  note?: string;
  ms: number;
}

/**
 * Runs a mandate's custom fields and rules in the rule engine (Fluxnova when configured, else the edge evaluator).
 * Fails closed: if the engine can't be reached, the result is not ok, so an unchecked order never goes through.
 */
export async function evaluateCustom(table: import('./dmn-compile').CustomTable, mandateId: string, variables: Vars, opts: { showDmn?: boolean; timeoutMs?: number } = {}): Promise<CustomResult> {
  const t = performance.now();
  if (LIVE) {
    // Live: the exchange is the engine that will run these rules at every auction, so it checks them too.
    try {
      const r = await exchange.checkCustomRules(table, variables);
      const ms = Math.round(performance.now() - t);
      const dmn = opts.showDmn ? compileDmn(table, customDecisionId(mandateId)) : undefined;
      if (!r.ok) return { ok: false, source: 'exchange', error: (r.problems ?? ['The exchange did not accept the rules.']).join(' '), dmn, ms };
      const o = r.outcome;
      return { ok: o?.action !== 'REJECT', source: 'exchange', engine: 'Fluxnova DMN in the exchange', action: o?.action, message: o?.message, dmn, ms };
    } catch (e) {
      return { ok: false, source: 'unavailable', error: e instanceof ExchangeError && e.status !== 0 ? e.message : 'The exchange is unreachable, so the custom rules could not be checked.', ms: Math.round(performance.now() - t) };
    }
  }
  try {
    const r = await fetch('/api/rules', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: 'custom', mandateId, table, variables, showDmn: opts.showDmn }), signal: AbortSignal.timeout(opts.timeoutMs ?? 6000),
    });
    const ms = Math.round(performance.now() - t);
    if (!r.headers.get('content-type')?.includes('json')) return { ok: false, source: 'unavailable', error: 'Rule engine not available here, so the custom rules could not be checked.', ms };
    const j = await r.json() as { ok?: boolean; source?: 'edge' | 'fluxnova'; engine?: string; hits?: RuleHit[]; dmn?: string; error?: string; note?: string };
    if (!r.ok || !j.hits) return { ok: false, source: j.source ?? 'unavailable', error: j.error ?? `Rule engine answered ${r.status}`, ms };
    const hit = j.hits[0];
    const action = (hit?.code.replace('CUSTOM_', '') ?? 'APPROVE') as CustomResult['action'];
    return { ok: action !== 'REJECT', source: j.source ?? 'edge', engine: j.engine, action, message: hit?.message, dmn: j.dmn, note: j.note, ms };
  } catch {
    return { ok: false, source: 'unavailable', error: 'Rule engine unreachable, so the custom rules could not be checked.', ms: Math.round(performance.now() - t) };
  }
}

/** Ask the DMN engine (via /api/rules); fall back to the local mirror after `timeoutMs`. */
export async function evaluate(decision: Decision, vars: Vars, timeoutMs = 4000): Promise<RuleResult> {
  const t = performance.now();
  try {
    const r = await fetch('/api/rules', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision, variables: vars }), signal: AbortSignal.timeout(timeoutMs),
    });
    if (!r.headers.get('content-type')?.includes('json')) return evaluateLocal(decision, vars, 'Rule engine not available here');
    const j = await r.json() as { ok?: boolean; hits?: RuleHit[]; error?: string; source?: string; note?: string };
    if (!r.ok || !Array.isArray(j.hits)) return evaluateLocal(decision, vars, j.error ?? `Rule engine answered ${r.status}`);
    return { decision, source: j.source === 'fluxnova' ? 'fluxnova' : 'edge', ok: !j.hits.some((h) => h.severity === 'BLOCK'), hits: j.hits, ms: Math.round(performance.now() - t), note: j.note };
  } catch {
    return evaluateLocal(decision, vars, 'Rule engine unreachable');
  }
}
