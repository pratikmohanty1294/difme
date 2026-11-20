import { useEffect, useState, type ReactNode } from 'react';
import {
  ArrowLeft, ArrowRight, BadgeCheck, Bell, Bot, Building2, Check, CreditCard, Fingerprint, Landmark, Loader2, RotateCcw,
  ShieldCheck, Sparkles, Store, Users, Wallet, Wrench,
} from 'lucide-react';
import { DISCOUNT_RULES, SCENARIO, SPLIT } from '../sim/data';
import { useEngine } from '../sim/engine';
import { hex, rid, time, inr, inr2 } from '../sim/format';
import { MCC_CATEGORIES, parseDelegation, type Delegation } from '../sim/nl';
import type { BuyRequest, Mandate, Notice, Party } from '../sim/types';
import { AuctionView } from './exchange/LiveAuction';
import { Badge, Callout, JsonView, KV, cx } from '../components/ui';
import { href } from '../router';
import { vic } from '../sim/vic';

const split = SPLIT;

const STEPS = [
  { short: 'Buying partner mandate', who: 'Buying Partner', tone: 'bg-investor-600' },
  { short: 'Buyer request', who: 'Buyer', tone: 'bg-buyer-600' },
  { short: 'OME auction', who: 'Exchange', tone: 'bg-exchange-600' },
  { short: 'Purchase & settlement', who: 'Settlement', tone: 'bg-audit-600' },
  { short: 'Buyer notified', who: 'Buyer', tone: 'bg-buyer-600' },
  { short: 'Yield to all parties', who: 'All parties', tone: 'bg-brand-600' },
];

const NARRATION: { title: string; text: ReactNode; parties: string[] }[] = [
  {
    title: 'The buying partner creates a smart mandate',
    text: <>You are a buying partner with a <b>Bank X Premium card</b> and ₹50,000 of idle limit. Tell Buying Partner what it may buy. It tokenises the card with the card network through the Agent PA/PG, runs a ₹0 card check, posts a refundable <b>security deposit of ₹2,000 (5% of the budget)</b> to escrow, and you approve one purchase instruction with your passkey: a ₹40,000 budget at Electronics Store A and Electronics Store B for electronics, valid 7 days. After that your agent buys with no human in the loop. No card is ever lent to anyone.</>,
    parties: ['Buying Partner', 'Buying Partner agent', 'Agent PA/PG', 'Agent credential', 'OME'],
  },
  {
    title: 'A buyer asks ShopBot for headphones',
    text: <>The buyer wants {SCENARIO.product}. ShopBot searches merchants over MCP and finds <b>Bank X's 20% instant discount</b> at Electronics Store A, but the buyer has no Bank X card. The buyer asks for a buying partner's agent to buy it and ship it to them, and ShopBot signs the buy request (Proof-of-Intent) with a max price of ₹9,500.</>,
    parties: ['Buyer', 'ShopBot agent', 'Merchants (MCP)'],
  },
  {
    title: 'The exchange runs a 50 ms auction',
    text: <>The OME checks the buyer's risk score (≥ 850), filters buying partners (₹0 card check, passkey re-checked in the last 7 days, deposit posted), locks the offer at Electronics Store A and runs the SBBA. Your bid of ₹600 scores <b>0.736</b> and wins provisionally at 50.2 ms; IAgent C (0.630) and A (0.560) stay on as hot backups.</>,
    parties: ['OME', 'Risk engine', 'Buying partner agents'],
  },
  {
    title: 'The purchase completes',
    text: <>The buyer's card is held for <b>B = ₹8,800</b> (Agent PA/PG). Your agent checks out at Electronics Store A (bill: you, ship: buyer) with a single-use the card network token for exactly <b>₹8,000</b>. Only after Electronics Store A's PSP approves is the buyer told "confirmed" (two-phase commit). On shipment the hold is captured and you are paid <b>₹8,600</b> before your card is due.</>,
    parties: ['Agent PA/PG', 'Buying partner agent', 'Agent credential', 'Electronics Store A + Merchant PSP', 'Bank X'],
  },
  {
    title: 'The buyer is notified',
    text: <>The buyer got a message at every state change: price found, hold placed, confirmed, shipped. The last one shows what they paid (₹8,800) and saved (₹1,200), and they can return the item: the refund comes straight from escrow in two legs.</>,
    parties: ['Buyer'],
  },
  {
    title: 'Money reaches every party',
    text: <>Electronics Store A received <b>₹8,000</b> from your card, you got <b>₹8,600</b> (₹600 yield, 7.5% for under 30 days), DiscountAI kept <b>₹200</b> (₹20 to the Guarantee Fund), and the buyer saved <b>₹1,200</b>. The regulator node received all five proofs within 500 ms.</>,
    parties: ['Buying Partner', 'Merchant', 'Platform', 'Regulator', 'Buyer'],
  },
];

export default function GuidedDemo() {
  const { state, reset } = useEngine();
  const [step, setStep] = useState(0);
  const [maxStep, setMaxStep] = useState(0);
  const [mandateId, setMandateId] = useState<string>();
  const [reqId, setReqId] = useState<string>();
  const [run, setRun] = useState(0);
  const req = state.requests.find((r) => r.id === reqId);

  const gates = [
    { ok: !!mandateId, wait: 'Deploy the mandate to continue.' },
    { ok: !!reqId, wait: 'Submit the buyer request to continue.' },
    { ok: !!req && req.status !== 'Pending', wait: 'Waiting for the auction to pick a winner…' },
    { ok: req?.status === 'Success', wait: 'Waiting for settlement to finish…' },
    { ok: true, wait: '' },
    { ok: false, wait: '' },
  ];
  const go = (n: number) => { setStep(n); setMaxStep((m) => Math.max(m, n)); window.scrollTo({ top: 0, behavior: 'smooth' }); };
  const restart = () => { reset(); setStep(0); setMaxStep(0); setMandateId(undefined); setReqId(undefined); setRun((r) => r + 1); };
  const n = NARRATION[step];

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="label mb-1.5">Guided demo · one transaction end to end</p>
          <h1 className="text-[26px] font-semibold tracking-tight text-ink-950">From mandate to yield, step by step</h1>
        </div>
        <button className="btn-ghost text-xs" onClick={restart}><RotateCcw size={14} /> Start over</button>
      </div>

      {/* step rail */}
      <ol className="grid grid-cols-3 md:grid-cols-6 gap-2" aria-label="Demo steps">
        {STEPS.map((s, i) => {
          const done = i < step || (i === step && i === 5 && req?.status === 'Success');
          const reachable = i <= maxStep;
          return (
            <li key={s.short} className="min-w-0">
              <button disabled={!reachable} onClick={() => go(i)} aria-current={i === step ? 'step' : undefined}
                className={cx('w-full text-left rounded-xl border px-3 py-2.5 transition-colors disabled:cursor-not-allowed',
                  i === step ? 'border-ink-900 bg-white ring-1 ring-ink-900' : reachable ? 'border-ink-200 bg-white hover:bg-ink-50' : 'border-ink-200 bg-ink-50 opacity-60')}>
                <span className="flex items-center gap-2">
                  <span className={cx('grid h-5 w-5 shrink-0 place-items-center rounded-full text-[11px] font-semibold text-white', done ? 'bg-exchange-600' : s.tone)}>{done ? <Check size={12} /> : i + 1}</span>
                  <span className="text-[11px] uppercase tracking-[0.06em] text-ink-500 truncate">{s.who}</span>
                </span>
                <span className="block mt-1 text-[13px] font-medium text-ink-900 truncate">{s.short}</span>
              </button>
            </li>
          );
        })}
      </ol>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_320px] items-start">
        <div className="min-w-0" key={`${run}-${step}`}>
          {step === 0 && <InvestorStep doneId={mandateId} onDone={setMandateId} />}
          {step === 1 && <BuyerStep submittedId={reqId} onSubmitted={(id) => { setReqId(id); go(2); }} />}
          {step === 2 && req && <AuctionStep req={req} />}
          {step === 3 && req && <SettlementStep req={req} />}
          {step === 4 && req && <BuyerNotified req={req} />}
          {step === 5 && req && <YieldStep req={req} onRestart={restart} />}
        </div>

        {/* narrator */}
        <aside className="card p-5 xl:sticky xl:top-20 flex flex-col gap-4">
          <div>
            <p className="label">Step {step + 1} of 6</p>
            <h2 className="mt-1 text-lg font-semibold leading-snug">{n.title}</h2>
          </div>
          <p className="text-[13.5px] text-ink-700 leading-relaxed">{n.text}</p>
          <div>
            <p className="label mb-1.5">Who's involved</p>
            <div className="flex flex-wrap gap-1.5">{n.parties.map((p) => <Badge key={p} tone="gray">{p}</Badge>)}</div>
          </div>
          <div className="border-t border-ink-100 pt-4 flex items-center justify-between gap-2">
            <button className="btn-ghost" disabled={step === 0} onClick={() => go(step - 1)}><ArrowLeft size={15} /> Back</button>
            {step < 5 && <button className="btn-primary" disabled={!gates[step].ok} onClick={() => go(step + 1)}>Next <ArrowRight size={15} /></button>}
          </div>
          {step < 5 && !gates[step].ok && <p className={cx('text-xs text-ink-500 -mt-2', step >= 2 && 'live-dot')}>{gates[step].wait}</p>}
        </aside>
      </div>
    </div>
  );
}

/* ---------------- shared bits ---------------- */

function SubStep({ n, title, state, children }: { n: number; title: string; state: 'todo' | 'active' | 'done'; children?: ReactNode }) {
  return (
    <li className={cx('rounded-xl border bg-white', state === 'active' ? 'border-ink-300 shadow-card' : 'border-ink-200')}>
      <div className="flex items-center gap-3 px-4 py-3">
        <span className={cx('grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs font-semibold',
          state === 'done' ? 'bg-exchange-600 text-white' : state === 'active' ? 'bg-ink-900 text-white' : 'bg-ink-100 text-ink-500')}>{state === 'done' ? <Check size={13} /> : n}</span>
        <span className={cx('font-medium', state === 'todo' ? 'text-ink-400' : 'text-ink-900')}>{title}</span>
      </div>
      {state === 'active' && children && <div className="px-4 pb-4 pl-[52px]">{children}</div>}
    </li>
  );
}

const useDelay = () => {
  const { state } = useEngine();
  return (ms: number) => new Promise((r) => setTimeout(r, state.speed === 'presenter' ? ms : state.speed === 'normal' ? ms * 0.35 : 0));
};

/* ---------------- 1 · buying partner ---------------- */

function InvestorStep({ doneId, onDone }: { doneId?: string; onDone: (id: string) => void }) {
  const { state, addMandate } = useEngine();
  const delay = useDelay();
  const [phase, setPhase] = useState(doneId ? 4 : 0);
  const [text, setText] = useState('Use my Bank X Premium card to buy electronics on Bank X offers, up to ₹10,000 per order, if yield is above 5%. Ship each item to the matched buyer. No gaming or gambling.');
  const [parsed, setParsed] = useState<Delegation>();
  const [busy, setBusy] = useState(false);
  const [deployed, setDeployed] = useState(0);
  const mandate = state.mandates.find((m) => m.id === doneId);
  const me = state.nodes.find((n) => n.isYou)!;
  const allowed = MCC_CATEGORIES.filter((c) => !(parsed?.mcc_exclusions ?? []).includes(c));
  const st = (i: number) => (phase > i ? 'done' : phase === i ? 'active' : 'todo') as 'todo' | 'active' | 'done';

  async function connect() { setBusy(true); await delay(1100); setBusy(false); setPhase(2); }
  async function deploy() {
    setBusy(true);
    for (let i = 1; i <= 4; i++) { await delay(750); setDeployed(i); }
    const id = `M-2026-${8830 + Math.floor(Math.random() * 160)}`;
    // Register the delegated purchase instruction with card-network agentic commerce (simulated until VISA_* secrets are set).
    const ins = await vic('instruction.create', { budgetTotal: 40000, maxPerOrder: parsed!.max_per_transaction, merchants: ['Electronics Store A', 'Electronics Store B'], goods: ['Electronics'], validityDays: 7, prompt: text });
    const m: Mandate = {
      id, name: 'Bank X card mandate', flow: 'A', assetType: 'FIAT_CREDIT', instrument: `Bank X · offer BIN ${SCENARIO.bin}`, bin: SCENARIO.bin,
      maxPerTx: parsed!.max_per_transaction, minYieldPct: parsed!.yield_target, mccRule: 'exclusion', mccs: parsed!.mcc_exclusions, regions: 'India',
      frequency: '3 / merchant / month', limit: 50000, utilised: 0, yieldEarned: 0, contract: `MND_M1 · vic_${hex(3)}`, version: 1, status: 'LIVE', nodeId: me.id,
      createdAt: new Date().toISOString(), deposit: 2000, variant: 'V1',
      budgetTotal: 40000, merchants: ['Electronics Store A', 'Electronics Store B'], goods: ['Electronics'], validityDays: 7,
      vicInstructionId: ins.ok ? String(ins.response?.instructionId ?? '') || undefined : undefined,
    };
    addMandate(m);
    setBusy(false);
    setPhase(4);
    onDone(id);
  }

  return (
    <div className="flex flex-col gap-4">
      <Header icon={<Wallet size={18} />} tone="bg-investor-50 text-investor-600" title="Buying Partner · smart mandate factory" sub="Buying partner app" />
      <ol className="flex flex-col gap-2.5">
        <SubStep n={1} title="Delegate in plain English" state={st(0)}>
          <label htmlFor="g-deleg" className="sr-only">Delegation</label>
          <textarea id="g-deleg" className="input min-h-[76px]" value={text} onChange={(e) => { setText(e.target.value); setParsed(undefined); }} />
          {!parsed ? (
            <button className="btn-primary mt-3" onClick={() => setParsed(parseDelegation(text))}><Sparkles size={15} /> Interpret instruction</button>
          ) : (
            <div className="mt-3 grid gap-3 lg:grid-cols-[minmax(0,1fr)_auto] items-end">
              <JsonView value={parsed} className="text-[11.5px]" />
              <button className="btn-primary" onClick={() => setPhase(1)}>Looks right <ArrowRight size={15} /></button>
            </div>
          )}
        </SubStep>
        <SubStep n={2} title="Tokenise the card with the card network (OTP + passkey)" state={st(1)}>
          <p className="text-sm text-ink-600">Bank X confirms it's you with an OTP, then the card network turns your card into an agent token, requested through our licensed payment partner (Agent PA/PG) and tied to your device passkey. Your card number stays with Bank X and the card network, and the agent token stays with our payment partner. DiscountAI keeps only your mandate ID, the offer BIN and the bank name.</p>
          <button className="btn-primary mt-3" disabled={busy} onClick={connect}>{busy ? <><Loader2 size={15} className="animate-spin" /> Waiting for Bank X OTP and passkey…</> : <><Landmark size={15} /> Verify with Bank X</>}</button>
        </SubStep>
        <SubStep n={3} title="Confirm the intent contract" state={st(2)}>
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="rounded-lg border border-ink-200 p-3">
              <p className="label mb-2">Asset record · BIN discount rules</p>
              <KV rows={[['Card', 'Bank X Premium card'], ['BIN', <span key="b" className="font-mono">{SCENARIO.bin}</span>], ['Mandate limit X', '₹50,000'], ...DISCOUNT_RULES[SCENARIO.bin].map((r) => [r.category, `${r.pct}% · ${r.promo}`] as [string, string])]} />
            </div>
            <div className="rounded-lg border border-ink-200 p-3">
              <p className="label mb-2">Smart mandate</p>
              <KV rows={[['max_per_order', inr(parsed?.max_per_transaction ?? 0)], ['min_yield_pct', `${parsed?.yield_target}%`], ['Allowed MCCs', allowed.join(', ')], ['Security deposit', '₹2,000 · 5% of budget, refundable'], ['Orders', '3 per merchant per month']]} />
            </div>
          </div>
          <button className="btn-primary mt-3" onClick={() => setPhase(3)}>Confirm rules <ArrowRight size={15} /></button>
        </SubStep>
        <SubStep n={4} title="Deposit, approve the mandate and go live" state={st(3)}>
          <ul className="flex flex-col gap-2 text-sm">
            {['₹0 card verification via the Agent PA/PG', '₹2,000 refundable security deposit posted to escrow + headroom covenant', 'Instruction approved once with your passkey · budget, merchants, goods, 7 days', 'Mandate ACTIVE in the order book · agent listening on 234.1.1.1:40123'].map((t, i) => (
              <li key={t} className="flex items-center gap-2.5">
                {deployed > i ? <Check size={15} className="text-exchange-600" /> : busy && deployed === i ? <Loader2 size={15} className="animate-spin text-ink-400" /> : <span className="h-[15px] w-[15px] rounded-full border border-ink-300" />}
                <span className={deployed > i ? 'text-ink-900' : 'text-ink-500'}>{t}</span>
              </li>
            ))}
          </ul>
          <button className="btn-primary mt-3" disabled={busy} onClick={deploy}>{busy ? <><Loader2 size={15} className="animate-spin" /> Deploying…</> : <><Fingerprint size={15} /> Sign and deploy</>}</button>
        </SubStep>
      </ol>
      {phase === 4 && mandate && (
        <Callout tone="good" title={`${mandate.id} is LIVE`}>
          Your agent is ready to buy within your budget and ship to matched buyers. <b>{me.label}</b> is online in {me.region} and will ask a {me.yieldAskPct.toFixed(1)}% yield with a {Math.round(me.reliability * 100)}% reliability record. Press <b>Next</b> to switch to the buyer.
        </Callout>
      )}
    </div>
  );
}

/* ---------------- 2 · buyer ---------------- */

function BuyerStep({ submittedId, onSubmitted }: { submittedId?: string; onSubmitted: (id: string) => void }) {
  const { submitRequest } = useEngine();
  const delay = useDelay();
  const [phase, setPhase] = useState<'ask' | 'searching' | 'options' | 'configure'>(submittedId ? 'configure' : 'ask');
  const [calls, setCalls] = useState<{ fn: string; args: string; result: string; bad?: boolean }[]>([]);
  const [mandate, setMandate] = useState<'none' | 'busy' | 'done'>('none');
  const [ref] = useState(() => rid('EMD-', 8).toUpperCase());
  const [consent, setConsent] = useState({ instrument: false, mandate: false });

  async function ask() {
    setPhase('searching');
    await delay(500); setCalls((c) => [...c, { fn: 'search_product', args: `"${SCENARIO.product}"`, result: '₹10,000 at Electronics Store A, Marketplace A and Marketplace B' }]);
    await delay(800); setCalls((c) => [...c, { fn: 'query_discount_promotions', args: 'store-a', result: 'Bank X: 20% instant discount (₹2,000) · BIN 437551 · programme V1' }]);
    await delay(800); setCalls((c) => [...c, { fn: 'check_buyer_eligibility', args: '"Bank X card"', result: 'Not eligible · no Bank X card in wallet', bad: true }]);
    await delay(500); setPhase('options');
  }

  return (
    <div className="flex flex-col gap-4">
      <Header icon={<Bot size={18} />} tone="bg-buyer-50 text-buyer-600" title="ShopBot AI · buyer agent" sub="Buyer app" />
      <div className="card p-4 flex flex-col gap-3">
        <div className="self-end max-w-[85%] rounded-2xl rounded-br-md bg-ink-900 text-white px-3.5 py-2 text-[13.5px]">{SCENARIO.query}</div>
        {phase === 'ask' && <button className="btn-primary self-end" onClick={ask}><Bot size={15} /> Ask ShopBot</button>}
        {calls.map((c) => (
          <div key={c.fn} className="rise rounded-lg border border-ink-200 text-[12.5px]">
            <div className="flex items-center gap-1.5 px-3 py-1.5 border-b border-ink-100 text-ink-500"><Wrench size={12} /> MCP tool call</div>
            <div className="px-3 py-2 font-mono text-[12px]">{c.fn}(<span className="text-buyer-600">{c.args}</span>)</div>
            <div className={cx('px-3 pb-2', c.bad ? 'text-red-700' : 'text-exchange-600')}>→ {c.result}</div>
          </div>
        ))}
        {phase === 'searching' && <p className="text-xs text-ink-500 flex items-center gap-2"><Loader2 size={13} className="animate-spin" /> Calling MCP tools…</p>}
      </div>

      {(phase === 'options' || phase === 'configure') && (
        <div className="card p-4">
          <p className="font-medium mb-3">Three ways to pay</p>
          <div className="grid gap-2.5 md:grid-cols-3">
            {[
              { t: 'A · Saved Bank X Millennial card', p: inr(9500), note: '5% cashback', icon: CreditCard },
              { t: 'B · Add a Bank X card', p: inr(split.A), note: "You don't hold one", icon: Store },
              { t: 'C · A buying partner buys it and ships it to me', p: `≈ ${inr(split.B)}`, note: `₹8,000 + ₹600 yield + ₹200 fee · saves ${inr(split.buyerSaves)}`, icon: Users, best: true },
            ].map((o) => (
              <div key={o.t} className={cx('rounded-xl border p-3', o.best ? (phase === 'configure' ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-brand-500') : 'border-ink-200 opacity-70')}>
                <o.icon size={16} className="text-ink-500" />
                <p className="mt-1.5 text-sm font-medium">{o.t}</p>
                <p className="text-xl font-semibold font-display tabular">{o.p}</p>
                <p className="text-xs text-ink-600">{o.note}</p>
              </div>
            ))}
          </div>
          {phase === 'options' && <button className="btn-primary mt-3" onClick={() => setPhase('configure')}>Choose C · a buying partner buys it and ships it to me <ArrowRight size={15} /></button>}
        </div>
      )}

      {phase === 'configure' && (
        <div className="card p-4">
          <p className="font-medium mb-3">Marketplace request</p>
          <div className="grid gap-4 lg:grid-cols-2">
            <KV rows={[['Wait for a buying partner', '30 min'], ['Max price', inr(SCENARIO.maxPrice)], ['Card hold', `B ≈ ${inr(split.B)}, only after a match`], ['Captured', 'when the item ships']]} />
            <div className="flex flex-col gap-2.5">
              {mandate === 'done' ? <Callout tone="good" title="e-Mandate authorised">Ref <span className="font-mono">{ref}</span></Callout> : (
                <button className="btn-secondary" disabled={mandate === 'busy'} onClick={async () => { setMandate('busy'); await delay(1000); setMandate('done'); }}>
                  {mandate === 'busy' ? <><Loader2 size={15} className="animate-spin" /> Waiting for passkey…</> : <><Fingerprint size={15} /> Authorise e-mandate with passkey</>}
                </button>
              )}
              <label className="flex items-start gap-2 text-sm"><input type="checkbox" className="mt-0.5 accent-brand-600" checked={consent.instrument} onChange={(e) => setConsent({ ...consent, instrument: e.target.checked })} />Let DiscountAI arrange it: a buying partner buys the item in their own name and ships it to me</label>
              <label className="flex items-start gap-2 text-sm"><input type="checkbox" className="mt-0.5 accent-brand-600" checked={consent.mandate} onChange={(e) => setConsent({ ...consent, mandate: e.target.checked })} />Hold the quoted amount on my card until shipment</label>
            </div>
          </div>
          <div className="mt-4 flex justify-end">
            <button className="btn-primary" disabled={!!submittedId || mandate !== 'done' || !consent.instrument || !consent.mandate}
              onClick={() => onSubmitted(submitRequest({ maxPrice: SCENARIO.maxPrice, expiry: '00:30', outcome: 'success', skipConfirm: true }))}>
              <ShieldCheck size={15} /> Sign intent and submit
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/* ---------------- 3 · auction ---------------- */

function AuctionStep({ req }: { req: BuyRequest }) {
  const { state } = useEngine();
  const w = state.nodes.find((n) => n.id === req.winnerNodeId);
  const winner = req.bids.find((b) => b.status === 'Won');
  const next = [...req.bids].filter((b) => b.status === 'Outbid').sort((a, b) => (b.score ?? 0) - (a.score ?? 0))[0];
  return (
    <div className="flex flex-col gap-4">
      <Header icon={<Building2 size={18} />} tone="bg-exchange-50 text-exchange-600" title="OME back office · live auction" sub="Exchange console" />
      {w && winner ? (
        <Callout tone="good" title={w.isYou ? 'Your Bank X mandate won the race' : `${w.label} won the race`}>
          {w.label} scored <b>{winner.score?.toFixed(3)}</b>{next ? <> against {next.nodeId}'s {next.score?.toFixed(3)}</> : null}. SBBA weighs yield, reliability and latency with each flow's own weights, so the best overall score wins, not simply the highest yield.
        </Callout>
      ) : <Callout tone="info" title="Auction in progress">Bids are arriving on the multicast channel…</Callout>}
      <AuctionView req={req} />
    </div>
  );
}

/* ---------------- 4 · settlement ---------------- */

const PROOFS = ['POI', 'POL', 'PRL', 'POE', 'POC'] as const;

function SettlementStep({ req }: { req: BuyRequest }) {
  const steps = req.timeline.filter((s) => ['PRL', 'POE', 'POC', 'FAIL'].includes(s.phase));
  return (
    <div className="flex flex-col gap-4">
      <Header icon={<ShieldCheck size={18} />} tone="bg-audit-50 text-audit-600" title={`Settlement · ${req.route ?? 'routing'}`} sub="Settlement gateway and proof chain" />
      <div className="card p-4 overflow-x-auto">
        <ol className="flex items-center gap-2 min-w-[560px]">
          {PROOFS.map((k, i) => {
            const has = req.proofs.some((p) => p.kind === k);
            return (
              <li key={k} className="flex items-center gap-2 flex-1">
                <span className={cx('flex-1 rounded-lg border px-3 py-2 text-center transition-colors', has ? 'border-exchange-600 bg-exchange-50' : 'border-ink-200')}>
                  <span className="block font-mono text-sm font-semibold">{k}</span>
                  <span className={cx('text-[11px]', has ? 'text-exchange-600' : 'text-ink-400')}>{has ? 'signed' : 'pending'}</span>
                </span>
                {i < 4 && <ArrowRight size={14} className="text-ink-300 shrink-0" />}
              </li>
            );
          })}
        </ol>
      </div>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="card p-4">
          <p className="font-medium mb-3">What happened after the match</p>
          <ol className="flex flex-col gap-2.5">
            {steps.map((s, i) => (
              <li key={i} className="rise flex gap-2.5 text-[13px]">
                <span className={cx('mt-0.5 h-fit rounded px-1.5 font-mono text-[10.5px]', s.phase === 'FAIL' ? 'bg-red-50 text-red-700' : 'bg-ink-100 text-ink-600')}>{s.phase}</span>
                <span><Badge tone="gray">{s.actor}</Badge> <span className="text-ink-800">{s.label}</span>{s.detail && <span className="block text-xs text-ink-500 mt-0.5">{s.detail}</span>}</span>
              </li>
            ))}
            {req.status !== 'Success' && <li className="text-xs text-ink-500 live-dot">Next settlement event…</li>}
          </ol>
        </div>
        <div className="card p-4">
          <p className="font-medium mb-3">Money movement</p>
          {req.split ? (
            <KV rows={[
              ['List price at Electronics Store A (P)', inr(req.split.list)],
              ['Bank X 20% instant discount', `−${inr(req.split.discount)}`],
              ['Charged to the buying partner card (A)', inr(req.split.A)],
              ['Buyer hold captured (B)', inr(req.split.B)],
              ['Buying partner payout (A + Y)', inr(req.split.payout)],
            ]} />
          ) : <p className="text-sm text-ink-500">Appears once the payment is captured and settled.</p>}
          {req.chainValid && <p className="mt-3 text-xs text-exchange-600 flex items-center gap-1.5"><BadgeCheck size={14} /> Proof chain verified: SHA-256 links and ECDSA signatures all pass.</p>}
          <a className="btn-secondary mt-4 w-full" href={href('audit', req.id)}>Open the full proof chain</a>
        </div>
      </div>
    </div>
  );
}

/* ---------------- 5 · buyer notified ---------------- */

function BuyerNotified({ req }: { req: BuyRequest }) {
  const { state } = useEngine();
  const notes = state.notifications.filter((x) => x.party === 'Buyer' && x.requestId === req.id);
  return (
    <div className="flex flex-col gap-4">
      <Header icon={<Bell size={18} />} tone="bg-buyer-50 text-buyer-600" title="Buyer's phone · ShopBot notifications" sub="Push notification on every state change" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)] items-start">
        <Phone title="ShopBot" notes={notes} />
        {req.split && (
          <div className="card p-5">
            <p className="label">Order confirmation</p>
            <h3 className="mt-1 text-xl font-semibold">{req.product}</h3>
            <p className="text-sm text-ink-500">{SCENARIO.productDetail} · Electronics Store A · <span className="font-mono">{req.orderId}</span></p>
            <div className="mt-4 rounded-xl bg-exchange-50 p-4">
              <p className="text-sm text-exchange-600">You paid</p>
              <p className="text-4xl font-semibold font-display tabular">{inr(req.split.B)}</p>
              <p className="text-sm text-exchange-600">and saved {inr(req.split.buyerSaves)} on the {inr(req.split.list)} price</p>
            </div>
            <div className="mt-4"><KV rows={[
              ['Bank X discount (buying partner\'s card)', `−${inr(req.split.discount)}`],
              ['Buying partner yield + fee', inr(req.split.Y + req.split.F)],
              ['Captured from your hold', inr(req.split.B)],
              ['Bought by', `Buying partner ${req.winnerNodeId} · shipped to you`],
            ]} /></div>
            <p className="mt-4 text-xs text-ink-500">You never held a Bank X card and no card was lent: the buying partner bought it with their own card and the merchant shipped it to you.</p>
          </div>
        )}
      </div>
    </div>
  );
}

/* ---------------- 6 · yield to all ---------------- */

const PARTY: Record<Party, { icon: typeof Bot; tone: string; who: string }> = {
  'Buying Partner': { icon: Wallet, tone: 'bg-investor-50 text-investor-600', who: 'You · Buying Partner' },
  Merchant: { icon: Store, tone: 'bg-exchange-50 text-exchange-600', who: 'Electronics Store A' },
  Platform: { icon: Building2, tone: 'bg-brand-50 text-brand-700', who: 'DiscountAI treasury' },
  Buyer: { icon: Bot, tone: 'bg-buyer-50 text-buyer-600', who: 'ShopBot user' },
  Regulator: { icon: Landmark, tone: 'bg-audit-50 text-audit-600', who: 'SEBI · RBI · Tax node' },
};

function YieldStep({ req, onRestart }: { req: BuyRequest; onRestart: () => void }) {
  const { state } = useEngine();
  const s = req.split!;
  const forParty = (p: Party) => state.notifications.filter((x) => x.party === p && (x.requestId === req.id || (!x.requestId && p === 'Buying Partner')));
  const headline: Record<Party, ReactNode> = {
    'Buying Partner': <span className="text-exchange-600">+{inr2(s.Y)}</span>,
    Merchant: <span className="text-exchange-600">+{inr(s.A)}</span>,
    Platform: <span className="text-exchange-600">+{inr2(s.F)}</span>,
    Buyer: <span>saved {inr(s.buyerSaves)}</span>,
    Regulator: <span className="text-audit-600">5 / 5 proofs</span>,
  };
  const sub: Record<Party, string> = {
    'Buying Partner': `Yield · payout ${inr(s.payout)}`, Merchant: 'Paid by the buying partner card', Platform: `Fee · ${inr(s.guaranteeFund)} to Guarantee Fund`,
    Buyer: `Paid ${inr(s.B)} on shipment`, Regulator: req.chainValid ? 'POC filed · chain verified' : 'POC filed',
  };
  const recon = s.A + s.Y + s.F === s.B;

  return (
    <div className="flex flex-col gap-4">
      <Header icon={<Users size={18} />} tone="bg-brand-50 text-brand-700" title="Settlement notifications · every party" sub="Parallel settlement" />
      <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
        {(['Buying Partner', 'Merchant', 'Platform', 'Buyer', 'Regulator'] as Party[]).map((p) => {
          const meta = PARTY[p];
          const notes = forParty(p);
          return (
            <section key={p} className="card flex flex-col">
              <header className="flex items-center gap-3 px-4 pt-4">
                <span className={cx('grid h-9 w-9 place-items-center rounded-lg', meta.tone)}><meta.icon size={17} /></span>
                <div className="min-w-0"><p className="font-semibold">{p}</p><p className="text-xs text-ink-500 truncate">{meta.who}</p></div>
              </header>
              <div className="px-4 pt-3">
                <p className="text-2xl font-semibold font-display tabular">{headline[p]}</p>
                <p className="text-xs text-ink-500">{sub[p]}</p>
              </div>
              <ul className="mt-3 border-t border-ink-100 divide-y divide-ink-100 flex-1">
                {notes.slice(0, 4).map((x) => <NoteRow key={x.id} n={x} />)}
              </ul>
            </section>
          );
        })}
      </div>
      <div className="card p-5">
        <p className="font-medium mb-3">Reconciliation</p>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="text-sm font-mono tabular leading-7">
            <p>A {inr(s.A)} + Y {inr(s.Y)} + F {inr(s.F)}</p>
            <p>= B {inr(s.B)} {recon ? <span className="text-exchange-600">✓</span> : <span className="text-red-700">✗</span>}</p>
            <p>Buyer saves P − B = {inr(s.buyerSaves)} <span className="text-exchange-600">✓</span></p>
          </div>
          <div className="flex flex-wrap items-start gap-2 md:justify-end">
            <a className="btn-secondary" href={href('investor')}>Buying partner command center</a>
            <a className="btn-secondary" href={href('audit/compliance')}>Regulator node</a>
            <button className="btn-primary" onClick={onRestart}><RotateCcw size={15} /> Run the journey again</button>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------------- small components ---------------- */

function Header({ icon, tone, title, sub }: { icon: ReactNode; tone: string; title: string; sub: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className={cx('grid h-9 w-9 place-items-center rounded-lg', tone)}>{icon}</span>
      <div><p className="label">{sub}</p><p className="font-semibold text-ink-900">{title}</p></div>
    </div>
  );
}

function NoteRow({ n }: { n: Notice }) {
  return (
    <li className="rise px-4 py-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <p className={cx('text-[13px] font-medium', n.tone === 'warn' ? 'text-amber-800' : 'text-ink-900')}>{n.title}</p>
        <span className="font-mono text-[10.5px] text-ink-400 shrink-0">{time(n.at)}</span>
      </div>
      <p className="text-xs text-ink-600 mt-0.5">{n.body}</p>
    </li>
  );
}

function Phone({ title, notes }: { title: string; notes: Notice[] }) {
  const [clock, setClock] = useState(() => new Date());
  useEffect(() => { const t = setInterval(() => setClock(new Date()), 30_000); return () => clearInterval(t); }, []);
  return (
    <div className="mx-auto w-full max-w-[320px] rounded-[38px] bg-ink-950 p-2.5 shadow-xl">
      <div className="rounded-[30px] bg-gradient-to-b from-[#1d3b4a] to-[#0f1d2b] px-3 pt-3 pb-6 min-h-[520px]">
        <div className="mx-auto h-5 w-24 rounded-full bg-black/70" />
        <p className="mt-6 text-center text-white/90 text-5xl font-light font-display tabular">{clock.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}</p>
        <p className="text-center text-white/60 text-xs">{clock.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
        <ul className="mt-6 flex flex-col gap-2">
          {notes.length === 0 && <li className="text-center text-xs text-white/50">No notifications yet</li>}
          {notes.map((n) => (
            <li key={n.id} className="rise rounded-2xl bg-white/85 backdrop-blur px-3 py-2.5">
              <div className="flex items-center gap-1.5 text-[10.5px] text-ink-500"><span className="grid h-4 w-4 place-items-center rounded bg-buyer-600 text-white"><Bot size={10} /></span>{title}<span className="ml-auto">{time(n.at).slice(0, 5)}</span></div>
              <p className="mt-1 text-[13px] font-semibold text-ink-900">{n.title}</p>
              <p className="text-[12px] text-ink-700 leading-snug">{n.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

