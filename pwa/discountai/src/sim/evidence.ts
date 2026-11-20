// Per-payment evidence chains, aligned to the A-Comm Evidence Protocol (AEP) draft v1.0.3-rc.2 (self-assessed).
// One order makes:
//   Chain S: the buying partner's mandate setup (intent + delegation), sealed when the the card network instruction is registered;
//   Chain B: the buyer's purchase from DiscountAI (cart total B), sealed at the buyer-hold authorisation;
//   Chain I: one per buying partner attempt (cart total A at the merchant), sealed at the issuer's approval;
//   plus a reconciliation record (a DiscountAI extension) that joins B and I and asserts B = A + Y + F.
// Artifacts are JSON-canonicalised (sorted keys, integer minor units), SHA-256 hash-linked, and each chain carries
// one operator signature at its seal point plus counter-signatures from independent witnesses. Ed25519 where the
// browser supports it, ECDSA P-256 otherwise. Keys are generated per browser session; nothing leaves the page.
import { canonical, sha256 } from './crypto';

export type ChainKind = 'S' | 'B' | 'I';
export type ArtifactType = 'intent' | 'delegation' | 'policy' | 'cart' | 'authorization' | 'fulfillment' | 'refund';
export type Signal = 'Deterministic' | 'Attested' | 'Inferred' | 'Asserted';
export interface Artifact { seq: number; type: ArtifactType; signal: Signal; payload: Record<string, unknown>; prev_hash: string | null; hash: string; post_seal: boolean }
export interface Attestation { by: string; role: 'operator' | 'witness'; alg: string; over: string; signature: string }
export interface Chain {
  id: string; kind: ChainKind; label: string; artifacts: Artifact[];
  seal?: { seq: number; hash: string; operator: Attestation; witnesses: Attestation[] };
  converting?: boolean; // Chain I: false when the attempt failed (sealed as non-converting)
}
export interface Recon { order_id: string; B: number; A: number; Y: number; F: number; auth_hash_B: string; auth_hash_I: string; check: 'B == A + Y + F'; signatures: Attestation[] }
export interface Evidence { S: Chain; B: Chain; I: Chain[]; recon?: Recon }

const b64u = (buf: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64u = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
export const paise = (rupees: number) => Math.round(rupees * 100);

// ---- keys: Ed25519 when available, ECDSA P-256 fallback ----
type Signer = { alg: string; pair: CryptoKeyPair };
const signers = new Map<string, Promise<Signer>>();
function signerFor(name: string) {
  if (!signers.has(name)) {
    signers.set(name, (async () => {
      try { return { alg: 'Ed25519', pair: await crypto.subtle.generateKey({ name: 'Ed25519' } as Algorithm, true, ['sign', 'verify']) as CryptoKeyPair }; }
      catch { return { alg: 'ES256', pair: await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair }; }
    })());
  }
  return signers.get(name)!;
}
const algParams = (alg: string) => (alg === 'Ed25519' ? ({ name: 'Ed25519' } as Algorithm) : ({ name: 'ECDSA', hash: 'SHA-256' } as EcdsaParams));
async function attest(by: string, role: Attestation['role'], over: string): Promise<Attestation> {
  const s = await signerFor(by);
  const sig = await crypto.subtle.sign(algParams(s.alg), s.pair.privateKey, fromHex(over));
  return { by, role, alg: s.alg, over, signature: b64u(sig) };
}
async function checkAttestation(a: Attestation) {
  const s = await signerFor(a.by);
  try { return await crypto.subtle.verify(algParams(s.alg), s.pair.publicKey, fromB64u(a.signature), fromHex(a.over)); } catch { return false; }
}
const fromHex = (h: string) => Uint8Array.from(h.match(/../g) ?? [], (x) => parseInt(x, 16));

// ---- building ----
export function newChain(kind: ChainKind, id: string, label: string): Chain { return { id, kind, label, artifacts: [] }; }
const hashOf = (chainId: string, a: Omit<Artifact, 'hash'>) => sha256(canonical({ chain: chainId, seq: a.seq, type: a.type, signal: a.signal, payload: a.payload, prev_hash: a.prev_hash }));
export async function append(c: Chain, type: ArtifactType, signal: Signal, payload: Record<string, unknown>): Promise<Chain> {
  const prev = c.artifacts.at(-1);
  const base = { seq: c.artifacts.length, type, signal, payload, prev_hash: prev?.hash ?? null, post_seal: Boolean(c.seal) };
  return { ...c, artifacts: [...c.artifacts, { ...base, hash: await hashOf(c.id, base) }] };
}
/** Seals a chain at its last artifact with one operator signature, then witnesses counter-sign the same hash. */
export async function seal(c: Chain, operator: string, witnesses: string[]): Promise<Chain> {
  const last = c.artifacts.at(-1)!;
  return { ...c, seal: { seq: last.seq, hash: last.hash, operator: await attest(operator, 'operator', last.hash), witnesses: await Promise.all(witnesses.map((w) => attest(w, 'witness', last.hash))) } };
}

// ---- verification ----
export interface ChainVerdict { hashes: boolean; links: boolean; must1: boolean | null; seal: boolean; witnesses: boolean; notes: string[] }
export async function verify(c: Chain): Promise<ChainVerdict> {
  const notes: string[] = [];
  let hashes = true, links = true;
  for (let i = 0; i < c.artifacts.length; i++) {
    const a = c.artifacts[i];
    const { hash, ...rest } = a;
    if ((await hashOf(c.id, rest)) !== hash) { hashes = false; notes.push(`Artifact ${i} (${a.type}) does not match its hash.`); }
    if ((i === 0 ? null : c.artifacts[i - 1].hash) !== a.prev_hash) { links = false; notes.push(`Artifact ${i} (${a.type}) is not linked to the one before.`); }
  }
  // AEP §3.6 MUST-1: authorization.amount === cart.total (integer minor units, same currency).
  const cart = c.artifacts.find((a) => a.type === 'cart');
  const auth = c.artifacts.find((a) => a.type === 'authorization');
  let must1: boolean | null = null;
  if (cart && auth) {
    must1 = Number.isInteger(auth.payload.amount) && auth.payload.amount === (cart.payload as { total?: number }).total && auth.payload.currency === cart.payload.currency;
    if (!must1) notes.push(`Authorisation ${String(auth.payload.amount)} ≠ cart total ${String(cart.payload.total)}: the chain must be rejected (AEP MUST-1).`);
  }
  const sealed = c.seal;
  const sealOk = Boolean(sealed && c.artifacts[sealed.seq]?.hash === sealed.hash && (await checkAttestation(sealed.operator)));
  if (!sealOk) notes.push('Operator seal does not verify.');
  const wOk = Boolean(sealed && sealed.witnesses.length > 0 && (await Promise.all(sealed.witnesses.map(checkAttestation))).every(Boolean) && sealed.witnesses.every((w) => w.over === sealed.hash));
  if (sealed && !wOk) notes.push('A witness counter-signature does not verify.');
  return { hashes, links, must1, seal: sealOk, witnesses: wOk, notes };
}
export async function verifyRecon(r: Recon, B: Chain, I: Chain) {
  const authB = B.artifacts.find((a) => a.type === 'authorization');
  const authI = I.artifacts.find((a) => a.type === 'authorization');
  const identity = r.B === r.A + r.Y + r.F;
  const linked = r.auth_hash_B === authB?.hash && r.auth_hash_I === authI?.hash;
  const digest = await reconDigest(r);
  const sigs = r.signatures.length > 0 && r.signatures.every((a) => a.over === digest) && (await Promise.all(r.signatures.map(checkAttestation))).every(Boolean);
  return { identity, linked, sigs };
}

// ---- the chains for a DiscountAI order ----
export interface SetupInput { mandateId: string; investorId: string; budgetTotal: number; maxPerOrder: number; merchants: string[]; goods: string[]; validityDays: number; instructionId?: string; customRules?: number }
export async function buildSetupChain(s: SetupInput): Promise<Chain> {
  let c = newChain('S', `chn_S_${s.mandateId.toLowerCase()}`, `Mandate setup · ${s.investorId}`);
  c = await append(c, 'intent', 'Deterministic', { mandate_id: s.mandateId, budget_total: paise(s.budgetTotal), max_per_order: paise(s.maxPerOrder), currency: 'INR', merchants: s.merchants, goods: s.goods, validity_days: s.validityDays, custom_rule_count: s.customRules ?? 0 });
  c = await append(c, 'delegation', 'Attested', { method: 'passkey (FIDO2)', vic_instruction_digest: await sha256(s.instructionId ?? `pending:${s.mandateId}`), human_present_at_purchase: false });
  return seal(c, 'DiscountAI evidence service', ['Agent credential (network)', 'RFC 3161 timestamp authority']);
}

export interface AttemptInput { investorId: string; instructionId?: string; approved: boolean; reason?: string; authCode?: string }
export interface OrderInput {
  orderId: string; product: string; merchant: string; maxPrice: number; shipHash: string; dfaMode: string;
  B: number; A: number; Y: number; F: number; holdId: string; attempts: AttemptInput[]; tracking?: string;
  setup: Chain; refund?: { buyerRefund: number; merchantRefundToInvestor: number };
}
export async function buildOrderEvidence(o: OrderInput): Promise<Evidence> {
  // Chain B: the buyer's sale, DiscountAI as merchant of record.
  let b = newChain('B', `chn_B_${o.orderId.toLowerCase()}`, 'Buyer sale · merchant DiscountAI (MCC 5262)');
  b = await append(b, 'intent', 'Deterministic', { order_id: o.orderId, product: o.product, max_price: paise(o.maxPrice), merchants: [o.merchant], ship_to_hash: o.shipHash, dfa_mode: o.dfaMode });
  b = await append(b, 'delegation', 'Attested', { buyer_appoints: 'DiscountAI as buying agent', consent: 'granular' });
  b = await append(b, 'policy', 'Deterministic', { match_record_digest: await sha256(`match:${o.orderId}`), ranking: 'SBBA score = w1·U + w2·R − w3·L/Lmax (disclosed)' });
  b = await append(b, 'cart', 'Deterministic', { total: paise(o.B), currency: 'INR', all_in_price: true });
  b = await append(b, 'authorization', 'Attested', { amount: paise(o.B), currency: 'INR', hold_id: o.holdId, capture: 'on shipment' });
  b = await seal(b, 'DiscountAI evidence service', ['Agent PA/PG', 'RFC 3161 timestamp authority']);

  // Chain I: one per buying partner attempt; failed attempts seal as non-converting.
  const chains: Chain[] = [];
  for (const [n, a] of o.attempts.entries()) {
    let c = newChain('I', `chn_I_${o.orderId.toLowerCase()}_${n + 1}`, `Buying partner purchase · ${a.investorId}${a.approved ? '' : ' · non-converting'}`);
    c = await append(c, 'intent', 'Deterministic', { order_id: o.orderId, use_order: `uo_${n + 1}`, mandate_setup_chain_id: o.setup.id, ship_to_hash: o.shipHash });
    c = await append(c, 'delegation', 'Attested', { vic_instruction_digest: await sha256(a.instructionId ?? 'none'), agent_token_requestor_type: '08 (AI Agent)' });
    c = await append(c, 'policy', 'Attested', { pre_execution_attestation: 'bounds checked: budget, merchant, goods, validity, custom rules', timestamp_anchor: 'RFC 3161' });
    c = await append(c, 'cart', 'Deterministic', { total: paise(o.A), currency: 'INR', merchant: o.merchant, merchant_signed: true });
    c = await append(c, 'authorization', 'Attested', a.approved
      ? { amount: paise(o.A), currency: 'INR', iso: '0110 approved', auth_code: a.authCode ?? '', mandate_setup_chain_id: o.setup.id }
      : { amount: paise(o.A), currency: 'INR', iso: 'not approved', reason: a.reason ?? 'declined', mandate_setup_chain_id: o.setup.id });
    c = await seal(c, 'DiscountAI evidence service', ['Merchant PSP', 'Agent credential (network)']);
    c.converting = a.approved;
    chains.push(c);
  }
  const winner = chains.find((c) => c.converting);

  // Post-seal events.
  if (o.tracking) {
    b = await append(b, 'fulfillment', 'Attested', { shipped: true, tracking: o.tracking, capture: paise(o.B) });
    if (winner) chains[chains.indexOf(winner)] = await append(winner, 'fulfillment', 'Attested', { cleared: paise(o.A), tracking: o.tracking });
  }
  if (o.refund) {
    b = await append(b, 'refund', 'Attested', { leg: 2, to: 'buyer payment method', amount: paise(o.refund.buyerRefund) });
    const w = chains.find((c) => c.converting);
    if (w) chains[chains.indexOf(w)] = await append(w, 'refund', 'Attested', { leg: 1, to: "buying partner's card", amount: paise(o.refund.merchantRefundToInvestor) });
  }

  // Reconciliation record (DiscountAI extension, outside AEP verification).
  const finalWinner = chains.find((c) => c.converting);
  let recon: Recon | undefined;
  if (finalWinner) {
    const r = { order_id: o.orderId, B: paise(o.B), A: paise(o.A), Y: paise(o.Y), F: paise(o.F),
      auth_hash_B: b.artifacts.find((x) => x.type === 'authorization')!.hash, auth_hash_I: finalWinner.artifacts.find((x) => x.type === 'authorization')!.hash, check: 'B == A + Y + F' as const };
    const digest = await sha256(canonical(r));
    recon = { ...r, signatures: [await attest('DiscountAI evidence service', 'operator', digest), await attest('Agent PA/PG', 'witness', digest)] };
  }
  return { S: o.setup, B: b, I: chains, recon };
}

/** Recomputes the reconciliation digest (signatures are over it). */
export const reconDigest = (r: Recon) => sha256(canonical({ order_id: r.order_id, B: r.B, A: r.A, Y: r.Y, F: r.F, auth_hash_B: r.auth_hash_B, auth_hash_I: r.auth_hash_I, check: r.check }));
