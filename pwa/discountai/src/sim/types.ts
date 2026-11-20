// Domain model for the DiscountAI prototype, aligned to DAI-ARCH-E2E-v4.1 (Master E2E Architecture).

export type Flow = 'A' | 'B' | 'C';
// v4.1 §3.1 L5: the buyer leg always runs on the Agent PA/PG; the purchase leg runs at Merchant M via its Merchant PSP.
export type SettlementRoute = 'Flow A · Card (V1)' | 'Flow B · USDC' | 'Flow C · UPI Reserve Pay' | 'Guarantee Fund backstop';
export type Actor =
  | 'Buyer' | 'Buyer Agent' | 'OME' | 'Risk Engine' | 'Aeron' | 'Buying Partner Agent' | 'Agent PA/PG' | 'Merchant PSP'
  | 'Agent credential' | 'Merchant' | 'Issuer' | 'Guarantee Fund' | 'Compliance' | 'Regulator';

// Two-phase commit: Matched is provisional; Confirmed only after Merchant PSP returns ISO 0110.
export type RequestStatus = 'Pending' | 'Matched' | 'Awaiting confirmation' | 'Confirmed' | 'Success' | 'Timed Out' | 'Cancelled' | 'Refunded';

/** The buyer's first-use choice for ShopBot's automated contracting (always / by criteria / never), and region. */
export interface BuyerPrefs {
  mode: 'always' | 'criteria' | 'never';
  maxPrice?: number;          // criteria: auto-confirm orders up to this price
  untilIso?: string;          // criteria: until this date
  region: 'IN' | 'US' | 'EU';
  chosenAt: string;
}
// Simulated outcomes. investor-decline → silent failover to the next bidder; all-decline → Guarantee Fund backstop.
export type Outcome = 'success' | 'investor-decline' | 'all-decline' | 'no-match';

export interface FlowWeights { w1: number; w2: number; w3: number }

export interface AuctionConfig {
  weights: Record<Flow, FlowWeights>;
  batchWindowMs: number;      // SBBA window (50 ms)
  latencyMaxMs: number;       // Lmax in the score formula
  minRiskScore: number;       // L3 pre-broadcast gate (850)
  intentTimeoutS: number;     // quote SLA (30 s)
  slashPct51: number;         // slash on ISO 51 decline, % of A
  slashPctOther: number;      // slash on other declines, % of A
  depositPct: number;         // buying partner security deposit α, % of mandate limit
  vppMaxDays: number;         // passkey re-check window in days (DiscountAI policy, not a network rule)
  vickrey: boolean;
  newNodeReliability: number;
}

export interface InvestorNode {
  id: string;            // e.g. IAgent-B
  label: string;         // display name
  owner: string;
  flow: Flow;
  assetClass: string;
  region: 'US-East-1 · Virginia' | 'EU-West-1 · Frankfurt' | 'AP-South-1 · Mumbai';
  yieldAskPct: number;   // yield asked, % of A (procurement commission)
  reliability: number;   // 0..1, 30-day EWMA
  latencyMs: number;     // bid response latency within the 50 ms window
  bin?: string;
  zkCapable: boolean;
  mandateId: string;
  activeMandates: number;
  queueDepth: number;
  zkAcceptance: number;  // 0..1
  fraudFlags: number;
  online: boolean;
  depositInr: number;    // Step 14b security deposit in Agent PA/PG escrow
  vppAgeDays: number;    // days since the last the card network Payment Passkey assertion
  isYou?: boolean;       // the buying partner persona in Buying Partner
}

export interface Bid {
  id: string;
  nodeId: string;
  flow: Flow;
  assetClass: string;
  yieldAsk: number;      // Y in ₹
  yieldAskPct: number;
  reliability: number;
  latencyMs: number;
  zkProof: 'n/a' | 'verified' | 'missing';
  receivedAtMs: number;
  score?: number;
  scoreParts?: { utility: number; utilPart: number; relPart: number; latPart: number; w: FlowWeights };
  rank?: number;
  status: 'Received' | 'Rejected' | 'Won' | 'Backup' | 'Outbid' | 'Declined';
  rejectReason?: string;
}

export interface TimelineStep {
  t: number;          // simulated time since intent submission, in ms
  actor: Actor;
  label: string;
  detail?: string;
  step?: string;      // v4.1 master step number(s)
  phase: 'POI' | 'POL' | 'PRL' | 'POE' | 'POC' | 'MCP' | 'FAIL' | 'REFUND';
}

export type ProofKind = 'POI' | 'POL' | 'PRL' | 'POE' | 'POC';

export interface ProofObject {
  kind: ProofKind;
  title: string;
  signer: string;
  payload: Record<string, unknown>;   // includes hash_prev
  hash: string;                        // SHA-256 of canonical payload
  signature: string;                   // ECDSA P-256 (base64url) over canonical payload
  createdAt: string;
}

/** v4.1 §0.4 conservation identity: A = P(1−d) · B = A + Y + F · S = P − B · payout = A + Y. */
export interface Split {
  list: number;        // P
  discountPct: number; // d
  discount: number;    // P × d
  A: number;           // charged to the buying partner's Bank X agent token at Merchant M
  Y: number;           // buying partner yield
  F: number;           // platform fee
  B: number;           // buyer pays (auth-only hold, captured on shipment)
  buyerSaves: number;  // S
  payout: number;      // A + Y to the buying partner before card due date
  guaranteeFund: number; // share of F allocated to the Guarantee Fund (10 %)
}

export type RefundCase = 'pre-payout' | 'post-payout' | 'partial';
export interface RefundRecord {
  case: RefundCase;
  at: string;
  merchantRefundToInvestor: number;  // Leg 1: Merchant M → Merchant PSP → buying partner card
  buyerRefund: number;               // Leg 2: OME → Agent PA/PG → buyer instrument
  investorClawback: number;          // principal + yield recovered from the buying partner
  feeReversal: number;
  recovery: string;
}

export interface BuyRequest {
  id: string;            // REQ-…
  auctionId: string;     // auc_…
  product: string;
  icon: 'laptop' | 'headphones' | 'sparkles' | 'monitor';
  merchant: string;
  mcc: string;
  instrument: string;    // qualifying instrument the offer needs
  bin: string;
  list: number;          // P
  quoteB: number;        // B quoted to the buyer
  maxPrice: number;      // buyer's max_price in the POI
  expiry: string;        // HH:MM wait time
  createdAt: string;     // ISO
  status: RequestStatus;
  outcome: Outcome;
  riskScore: number;
  timeline: TimelineStep[];
  bids: Bid[];
  winnerNodeId?: string;
  backupIds?: string[];
  failedNodeIds?: string[];
  route?: SettlementRoute;
  split?: Split;
  buyerHoldId?: string;
  proofs: ProofObject[];
  chainValid?: boolean;
  orderId?: string;
  trackingId?: string;
  failReason?: string;
  confirmDeadline?: string;   // buyer must confirm the quote by this time (fails closed)
  confirmReason?: string;     // why confirmation was required (EU fee rule, chosen mode, criteria)
  confirmedAt?: string;
  evidence?: import('./evidence').Evidence;          // per-payment chains S, B, I + reconciliation record
  evidenceInput?: import('./evidence').OrderInput;   // kept to append refund events
  /** Risk-tiered split release of the buying partner payout. */
  payoutPlan?: { tier: 0 | 1; principal: number; yield: number; principalTo: string; principalPaidAt?: string; releaseAt: string };
  refunds?: RefundRecord[];
  payoutDone?: boolean;
  investorYieldPaid?: number; // Y actually paid (a failover backup is paid its own ask)
  seeded?: boolean;
}

export interface Mandate {
  id: string;                 // M-2024-8821
  name: string;
  flow: Flow;
  assetType: 'FIAT_CREDIT' | 'UPI_VAULT' | 'TOKENIZED_RWA' | 'STABLECOIN';
  instrument: string;
  bin?: string;
  maxPerTx: number;           // max_per_order
  minYieldPct: number;
  mccRule: 'inclusion' | 'exclusion';
  mccs: string[];
  regions: string;
  frequency: string;
  limit: number;              // X
  utilised: number;           // settled + reserved
  yieldEarned: number;
  contract: string;
  version: number;
  status: 'LIVE' | 'Paused' | 'Revoked';
  nodeId: string;
  createdAt: string;
  deposit?: number;           // security deposit: the exchange's deposit rate × the total budget, held in escrow
  depositReturned?: number;   // deposit released back to the buying partner when the mandate ends
  variant?: 'V1' | 'V2' | 'V3';
  intentType?: 'Delegated budget · no human present';
  vppRefreshDue?: string;     // ISO, when the instruction expires and must be confirmed again with the passkey
  // Delegated purchase instruction (card-network agent pay: budget, goods, approved merchants, validity; Agent credential instruction)
  budgetTotal?: number;       // total the agent may spend while the instruction is valid
  merchants?: string[];       // approved merchants
  goods?: string[];           // goods categories the agent may buy
  validityDays?: number;      // at most 7 (DiscountAI policy)
  vicInstructionId?: string;  // card-network agentic commerce purchase instruction for this mandate
  customRules?: import('./dmn-compile').CustomTable; // buying partner's own fields + rules, compiled to DMN, run at each auction
  setupChain?: import('./evidence').Chain;            // evidence Chain S for this mandate
}

export interface FeedItem {
  id: string;
  at: string;          // ISO
  kind: 'bid' | 'won' | 'outbid' | 'settled' | 'mandate' | 'config' | 'failed' | 'refund';
  text: string;
  amount?: number;
  nodeId?: string;
}

export interface AuditEntry { at: string; who: string; change: string }

export type Party = 'Buyer' | 'Buying Partner' | 'Merchant' | 'Platform' | 'Regulator';
export interface Notice {
  id: string;
  at: string;
  party: Party;
  title: string;
  body: string;
  amount?: number;      // signed money movement for this party
  tone?: 'good' | 'info' | 'warn';
  requestId?: string;
}

export interface RegulatorEvent { at: string; proof: ProofKind | 'ALERT'; requestId: string; latencyMs: number; text: string }

export type Speed = 'presenter' | 'normal' | 'instant';
