import { useState } from 'react';
import { ArrowLeft, ArrowRight, Check, CreditCard, Landmark, Loader2, Sparkles, Wallet } from 'lucide-react';
import { DISCOUNT_RULES } from '../../sim/data';
import { useEngine } from '../../sim/engine';
import { hex, inr } from '../../sim/format';
import type { Flow, Mandate } from '../../sim/types';
import { Badge, Callout, Card, JsonView, KV, PageHeader, Segmented, Stepper, cx } from '../../components/ui';
import { href } from '../../router';
import { evaluate, evaluateCustom, type CustomResult, type RuleResult } from '../../sim/rules';
import { DEFAULT_CUSTOM, sampleOrder, type CustomTable } from '../../sim/dmn-compile';
import { CustomRulesEditor } from './CustomRulesEditor';
import { RuleResultView, SOURCE } from '../../components/RuleResultView';
import { vic, type VicResult } from '../../sim/vic';
import { VicCallView } from '../../components/VicCallView';
import { LIVE } from '../../api/exchange';

import { MCC_CATEGORIES as CATS, parseDelegation, type Delegation as Parsed } from '../../sim/nl';

type Path = 'card' | 'upi' | 'finternet';
// Delegated purchase instruction: what the networks need before an agent may buy with no human in the loop.
const APPROVED_MERCHANTS = ['Electronics Store A', 'Electronics Store B', 'Marketplace A', 'Marketplace B', 'Group T CLiQ'];
const GOODS = ['Electronics', 'Home appliances', 'Fashion', 'Gift cards', 'Travel'];

export default function MandateFactory() {
  const { state, addMandate } = useEngine();
  const [step, setStep] = useState(0);
  const [text, setText] = useState('Use my Bank X card to buy electronics on Bank X offers, up to ₹10,000 per order, if yield is above 5%. Ship each item to the matched buyer. No gaming or gambling.');
  const [parsed, setParsed] = useState<Parsed>();
  const [path, setPath] = useState<Path>('card');
  const [connected, setConnected] = useState(false);
  const [rules, setRules] = useState({ maxPerTx: 10000, minYield: 5, mccRule: 'exclusion' as 'inclusion' | 'exclusion', mccs: ['Gaming', 'Gambling'], regions: 'India, US, EU', frequency: 10, stayMin: 30,
    budgetTotal: 40000, merchants: ['Electronics Store A', 'Electronics Store B'], goods: ['Electronics'], validityDays: 7 });
  const validUntil = new Date(Date.now() + rules.validityDays * 864e5).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  // Security deposit: the exchange's deposit rate (Exchange OME › Auction config) applied to this mandate's total budget.
  const depositPct = state.config.depositPct;
  // As the exchange requires (decided 2026-11-21): the larger of the budget rate and the FR-14 collateral on the largest
  // allowed order for a new partner, max(₹500, A · α 0.20 · γ 1.8 · φ 2.0) (verticals/fr01-buy/vertical.yaml).
  const onBudget = Math.ceil((rules.budgetTotal * depositPct) / 100);
  const onLargestOrder = rules.maxPerTx > 0 ? Math.max(500, Math.ceil(rules.maxPerTx * 0.2 * 1.8 * 2.0)) : 0;
  const deposit = Math.max(onBudget, onLargestOrder);
  const depositBasis = deposit === onBudget ? `${depositPct}% of budget` : `covers your largest order (${inr(rules.maxPerTx)}) as a new partner`;
  const slashNote = `${state.config.slashPct51}% of the order amount`;
  const toggle = (list: string[], v: string, on: boolean) => (on ? [...list, v] : list.filter((x) => x !== v));
  const [tab, setTab] = useState<'standard' | 'custom'>('standard');
  // Custom fields and rules: compiled to a DMN decision and run in the rule engine at every auction.
  const [draftId] = useState(() => `M-2026-${8830 + Math.floor(Math.random() * 160)}`);
  const [custom, setCustom] = useState<CustomTable>(DEFAULT_CUSTOM);
  const [useCustom, setUseCustom] = useState(true);
  const [sample, setSample] = useState<Record<string, string | number | boolean>>(sampleOrder);
  const [customCheck, setCustomCheck] = useState<CustomResult>();
  const [deploy, setDeploy] = useState(0);
  const [mandate, setMandate] = useState<Mandate>();
  const [check, setCheck] = useState<RuleResult>();
  const [checking, setChecking] = useState(false);
  const [vicCalls, setVicCalls] = useState<{ title: string; r: VicResult }[]>([]);
  const limitFor = (p: Path) => (p === 'card' ? 50000 : p === 'upi' ? 40000 : 50000);

  const flow: Flow = path === 'card' ? 'A' : path === 'upi' ? 'C' : 'B';

  function runParse() {
    const p = parseDelegation(text);
    setParsed(p);
    setRules((r) => ({ ...r, maxPerTx: p.max_per_transaction, minYield: p.yield_target, mccs: p.mcc_exclusions, mccRule: 'exclusion' }));
    setPath(p.asset_preferences === 'UPI_VAULT' ? 'upi' : p.asset_preferences === 'FIAT_CREDIT' ? 'card' : 'finternet');
  }

  async function runDeploy() {
    // Step 1: the intent goes through the DMN rule engine (mandate_validation) before anything is deployed.
    setChecking(true);
    const limit = limitFor(path);
    // Live: the exchange runs mandate_validation itself when the mandate is registered (addMandate) and answers with its
    // findings; a browser pre-check against /api/rules would only 404 there.
    const r = LIVE ? { ok: true } as RuleResult : await evaluate('mandate_validation', {
      maxPerOrder: rules.maxPerTx, limit, minYieldPct: rules.minYield, deposit, depositRequired: deposit,
      budgetTotal: rules.budgetTotal, merchantCount: rules.merchants.length, goodsCount: rules.goods.length, validityDays: rules.validityDays,
      cardJurisdiction: path === 'card' ? 'IN_DOMESTIC_AFA' : 'NOT_CARD',
    });
    if (!LIVE) setCheck(r);
    if (!r.ok) { setChecking(false); return; }
    // Custom fields and rules: compile and run once in the rule engine; a syntax error or an unreachable engine stops the deploy.
    if (useCustom) {
      const cr = await evaluateCustom(custom, draftId, sample);
      setCustomCheck(cr);
      if (cr.error) { setChecking(false); return; }
    } else setCustomCheck(undefined);
    // Card path: enrol the card with card-network agentic commerce, then register the delegated purchase instruction.
    let vicInstructionId: string | undefined;
    if (path === 'card') {
      const enr = await vic('enroll', {});
      const ins = enr.ok ? await vic('instruction.create', {
        budgetTotal: rules.budgetTotal, maxPerOrder: rules.maxPerTx, merchants: rules.merchants, goods: rules.goods, validityDays: rules.validityDays, prompt: text,
      }) : undefined;
      setVicCalls([{ title: 'Enrol card with the card network', r: enr }, ...(ins ? [{ title: 'Register purchase instruction', r: ins }] : [])]);
      if (!ins?.ok) { setChecking(false); return; }
      vicInstructionId = String(ins.response?.instructionId ?? '');
    }
    setChecking(false);
    setDeploy(1);
    for (let i = 2; i <= 5; i++) { await new Promise((res) => setTimeout(res, state.speed === 'instant' ? 0 : 900)); setDeploy(i); }
    const id = draftId;
    const m: Mandate = {
      id, name: path === 'card' ? 'Bank X credit line' : path === 'upi' ? 'UPI block fund' : 'USDC vault · Base', flow,
      assetType: path === 'card' ? 'FIAT_CREDIT' : path === 'upi' ? 'UPI_VAULT' : 'STABLECOIN',
      instrument: path === 'card' ? 'Bank X · offer BIN 437551' : path === 'upi' ? 'Bank X savings · UPI Reserve' : 'USDC · Base L2',
      bin: path === 'card' ? '437551' : undefined, maxPerTx: rules.maxPerTx, minYieldPct: rules.minYield, mccRule: rules.mccRule, mccs: rules.mccs,
      regions: rules.regions, frequency: `${rules.frequency} / day`, limit, utilised: 0, yieldEarned: 0, deposit,
      contract: path === 'upi' ? `NPCI-MND-${hex(3).toUpperCase()}` : `0xabc${hex(2)}…${hex(2)}`, version: 1, status: 'LIVE', nodeId: 'IAgent-Y', createdAt: new Date().toISOString(),
      variant: path === 'card' ? 'V1' : undefined,
      budgetTotal: rules.budgetTotal, merchants: rules.merchants, goods: rules.goods, validityDays: rules.validityDays, vicInstructionId,
      customRules: useCustom ? custom : undefined,
      intentType: path === 'card' ? 'Delegated budget · no human present' : undefined,
      vppRefreshDue: new Date(Date.now() + rules.validityDays * 864e5).toISOString(),
    };
    setMandate(m);
    addMandate(m);
  }

  const steps = ['Delegate', 'Connect asset', 'Intent contract', 'Deploy & register'];
  return (
    <>
      <PageHeader eyebrow="Buying Partner" title="Smart mandate factory"
        desc="Tell Buying Partner what it may buy for you: a budget, approved merchants, the goods and how long the instruction lasts. You approve it once with your passkey; the agent then buys with no human in the loop, as the purchaser, and ships each item to the matched buyer's address. No card is ever lent." />
      <div className="mb-5"><Stepper steps={steps} current={step} /></div>

      {step === 0 && (
        <Card title="1 · Delegate in plain English" sub="The agent turns your instruction into structured limits">
          <div className="grid gap-5 lg:grid-cols-2">
            <div>
              <label htmlFor="delegation" className="font-medium text-ink-900">Your instruction</label>
              <textarea id="delegation" className="input mt-2 min-h-[120px]" value={text} onChange={(e) => { setText(e.target.value); setParsed(undefined); }} />
              <div className="mt-2 flex flex-wrap gap-2">
                {['Use my USDC for orders under ₹25,000 if yield is above 6%', 'Block ₹1 lakh via UPI for groceries and travel, conservative'].map((s) => (
                  <button key={s} className="text-left text-xs rounded-full border border-ink-200 px-3 py-1 text-ink-600 hover:bg-ink-50" onClick={() => { setText(s); setParsed(undefined); }}>{s}</button>
                ))}
              </div>
              <button className="btn-primary mt-4" onClick={runParse}><Sparkles size={15} /> Interpret instruction</button>
            </div>
            <div>
              <p className="label mb-2">Structured delegation</p>
              {parsed ? <JsonView value={parsed} /> : <div className="rounded-lg border border-dashed border-ink-200 p-6 text-sm text-ink-500">The agent extracts risk_tolerance, yield_target, asset_preferences, mcc_exclusions and max_per_transaction.</div>}
              {parsed && <p className="mt-2 text-xs text-ink-500">Check these values. You can change any of them in step 3.</p>}
            </div>
          </div>
          <div className="mt-5 flex justify-end"><button className="btn-primary" disabled={!parsed} onClick={() => setStep(1)}>Continue <ArrowRight size={15} /></button></div>
        </Card>
      )}

      {step === 1 && (
        <Card title="2 · Connect the asset" sub="Token Manager offers three registration paths">
          <div className="grid gap-3 md:grid-cols-3 mb-5">
            {([
              ['card', CreditCard, 'Card / fiat credit', 'Flow A · issuer OAuth2, BIN discount mapping, bank earmark'],
              ['upi', Landmark, 'UPI block fund', 'Flow C · TPAP mandate, NPCI blocks funds at your bank'],
              ['finternet', Wallet, 'Finternet / RWA', 'Flow B · on-chain mandate token, ZK solvency proofs'],
            ] as const).map(([k, Icon, t, d]) => (
              <button key={k} onClick={() => { setPath(k); setConnected(false); }}
                className={cx('flex flex-col items-start justify-start text-left rounded-xl border p-4 transition-colors', path === k ? 'border-investor-600 bg-investor-50 ring-1 ring-investor-600' : 'border-ink-200 hover:bg-ink-50')}>
                <Icon size={18} className="text-investor-600" />
                <p className="mt-2 font-medium">{t}</p>
                <p className="text-[12.5px] text-ink-600 mt-0.5">{d}</p>
              </button>
            ))}
          </div>
          {path === 'card' && <CardConnect done={connected} onDone={() => setConnected(true)} />}
          {path === 'upi' && <UpiConnect done={connected} onDone={() => setConnected(true)} />}
          {path === 'finternet' && <ChainConnect done={connected} onDone={() => setConnected(true)} />}
          <div className="mt-5 flex justify-between">
            <button className="btn-ghost" onClick={() => setStep(0)}><ArrowLeft size={15} /> Back</button>
            <button className="btn-primary" disabled={!connected} onClick={() => setStep(2)}>Continue <ArrowRight size={15} /></button>
          </div>
        </Card>
      )}

      {step === 2 && (
        <Card title="3 · Define the intent contract" sub="Standard template or a custom decision table"
          right={<Segmented value={tab} onChange={setTab} options={[{ value: 'standard', label: 'Standard limits' }, { value: 'custom', label: 'Custom fields & rules' }]} />}>
          {tab === 'standard' ? (
            <>
            <fieldset className="mb-6 rounded-xl border border-investor-100 bg-investor-50/40 p-4">
              <legend className="px-1 font-medium text-ink-900">Delegated purchase instruction <span className="text-xs font-normal text-ink-500">· no human in the loop at purchase</span></legend>
              <p className="text-[13px] text-ink-600">the card network and Card-network rules let an agent buy without you present only within a budget, approved merchants, a description of the goods and a validity period that you set and approve with your passkey.</p>
              <div className="mt-3 grid gap-4 md:grid-cols-2">
                <div>
                  <label htmlFor="budget" className="font-medium">Total budget</label>
                  <div className="mt-1.5 flex items-center gap-2"><span className="text-ink-500">₹</span><input id="budget" className="input w-40 tabular" inputMode="numeric" value={rules.budgetTotal} onChange={(e) => setRules({ ...rules, budgetTotal: Number(e.target.value.replace(/\D/g, '')) })} /></div>
                  <p className="mt-1 text-xs text-ink-500">Most the agent may spend while the instruction is valid.</p>
                  <p className="mt-2 inline-flex flex-wrap gap-x-1 rounded-md bg-white px-2 py-1 text-xs text-ink-600 ring-1 ring-investor-100">Security deposit <b className="tabular text-ink-900">{inr(deposit)}</b> · {depositBasis} · refundable</p>
                </div>
                <div>
                  <label htmlFor="validity" className="font-medium">Valid for</label>
                  <div className="mt-1.5 flex items-center gap-2"><input id="validity" className="input w-20 tabular" inputMode="numeric" value={rules.validityDays} onChange={(e) => setRules({ ...rules, validityDays: Number(e.target.value.replace(/\D/g, '')) })} /><span className="text-sm text-ink-500">days (up to 7) · until {validUntil}</span></div>
                  <p className="mt-1 text-xs text-ink-500">Then you approve a new instruction with your passkey.</p>
                </div>
                <div>
                  <p className="font-medium">Approved merchants</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {APPROVED_MERCHANTS.map((m) => (
                      <label key={m} className="flex items-center gap-2 rounded-lg border border-ink-200 bg-white px-3 py-1.5 text-sm">
                        <input type="checkbox" className="accent-investor-600" checked={rules.merchants.includes(m)} onChange={(e) => setRules({ ...rules, merchants: toggle(rules.merchants, m, e.target.checked) })} />{m}
                      </label>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="font-medium">Goods the agent may buy</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {GOODS.map((g) => (
                      <label key={g} className="flex items-center gap-2 rounded-lg border border-ink-200 bg-white px-3 py-1.5 text-sm">
                        <input type="checkbox" className="accent-investor-600" checked={rules.goods.includes(g)} onChange={(e) => setRules({ ...rules, goods: toggle(rules.goods, g, e.target.checked) })} />{g}
                      </label>
                    ))}
                  </div>
                </div>
              </div>
              <p className="mt-3 text-xs text-ink-500">You are the purchaser of record on every order. The only thing that changes per order is the delivery address: each item ships to the buyer DiscountAI matched.</p>
            </fieldset>
            <div className="grid gap-6 lg:grid-cols-2">
              <div className="flex flex-col gap-4">
                <div>
                  <label htmlFor="maxtx" className="font-medium">Max per transaction</label>
                  <div className="mt-1.5 flex items-center gap-2"><span className="text-ink-500">₹</span><input id="maxtx" className="input w-40 tabular" inputMode="numeric" value={rules.maxPerTx} onChange={(e) => setRules({ ...rules, maxPerTx: Number(e.target.value.replace(/\D/g, '')) })} /></div>
                </div>
                <div>
                  <label htmlFor="yf" className="font-medium flex justify-between">Minimum yield floor <span className="tabular text-investor-600">{rules.minYield.toFixed(1)}%</span></label>
                  <input id="yf" type="range" min={0.5} max={5} step={0.1} value={rules.minYield} onChange={(e) => setRules({ ...rules, minYield: Number(e.target.value) })} className="w-full mt-2 accent-investor-600" />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div><label htmlFor="freq" className="font-medium">Frequency limit</label><div className="mt-1.5 flex items-center gap-2"><input id="freq" className="input w-20 tabular" value={rules.frequency} onChange={(e) => setRules({ ...rules, frequency: Number(e.target.value.replace(/\D/g, '')) })} /><span className="text-sm text-ink-500">per day</span></div></div>
                  <div><label htmlFor="stay" className="font-medium">Max hold (stay)</label><div className="mt-1.5 flex items-center gap-2"><input id="stay" className="input w-20 tabular" value={rules.stayMin} onChange={(e) => setRules({ ...rules, stayMin: Number(e.target.value.replace(/\D/g, '')) })} /><span className="text-sm text-ink-500">min</span></div></div>
                </div>
                <div><label htmlFor="geo" className="font-medium">Geographic scope</label><input id="geo" className="input mt-1.5" value={rules.regions} onChange={(e) => setRules({ ...rules, regions: e.target.value })} /></div>
              </div>
              <div>
                <p className="font-medium">Merchant categories</p>
                <div className="mt-2"><Segmented size="sm" value={rules.mccRule} onChange={(v) => setRules({ ...rules, mccRule: v })} options={[{ value: 'exclusion', label: 'Block ticked' }, { value: 'inclusion', label: 'Allow only ticked' }]} /></div>
                <div className="mt-3 grid grid-cols-2 gap-2">
                  {CATS.map((c) => (
                    <label key={c} className="flex items-center gap-2 rounded-lg border border-ink-200 px-3 py-2 text-sm">
                      <input type="checkbox" className="accent-investor-600" checked={rules.mccs.includes(c)} onChange={(e) => setRules({ ...rules, mccs: e.target.checked ? [...rules.mccs, c] : rules.mccs.filter((x) => x !== c) })} />{c}
                    </label>
                  ))}
                </div>
                <Callout tone="info" title="Allowed categories">
                  {(rules.mccRule === 'exclusion' ? CATS.filter((c) => !rules.mccs.includes(c)) : rules.mccs).join(', ') || 'None. The mandate would never pay.'}
                </Callout>
              </div>
            </div>
            </>
          ) : (
            <div className="flex flex-col gap-4">
              <label className="flex items-start gap-2.5 text-sm">
                <input type="checkbox" className="mt-0.5 accent-investor-600" checked={useCustom} onChange={(e) => setUseCustom(e.target.checked)} />
                <span><b>Apply these custom rules to every order</b>, on top of the standard limits. They run in the rule engine at each auction; a Reject stops your agent bidding, a Flag lets it bid and notes it.</span>
              </label>
              <CustomRulesEditor value={custom} onChange={setCustom} sample={sample} onSample={setSample} mandateId={draftId} />
            </div>
          )}
          <div className="mt-5 flex justify-between">
            <button className="btn-ghost" onClick={() => setStep(1)}><ArrowLeft size={15} /> Back</button>
            <button className="btn-primary" disabled={rules.maxPerTx <= 0} onClick={() => setStep(3)}>Review <ArrowRight size={15} /></button>
          </div>
        </Card>
      )}

      {step === 3 && (
        <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
          <Card title="4 · Contract parameters" sub="Encoded in the ERC-1155 mandate token (L7)">
            <KV rows={[
              ['Asset path', path === 'card' ? 'Flow A · Bank X Premium card (V1)' : path === 'upi' ? 'Flow C · UPI block fund' : 'Flow B · USDC on Base'],
              ['Instruction', 'Delegated budget · no human in the loop at purchase'],
              ['budget_total', inr(rules.budgetTotal)],
              ['security_deposit', `${inr(deposit)} · ${depositBasis}, refundable`],
              ['approved_merchants', rules.merchants.join(', ') || '—'],
              ['goods', rules.goods.join(', ') || '—'],
              ['valid_until', `${validUntil} (${rules.validityDays} days)`],
              ['purchaser', 'You (cardholder) · ship-to: matched buyer, per order'],
              ['custom_rules', useCustom ? `${custom.fields.length} fields (${custom.fields.map((f) => f.key).join(', ')}) · ${custom.rules.length} rules · default ${custom.defaultAction.toLowerCase()}` : 'none'],
              ['max_per_tx', inr(rules.maxPerTx)],
              ['min_yield', `${rules.minYield.toFixed(1)}%`],
              ['allowed_MCCs', rules.mccRule === 'exclusion' ? `all except ${rules.mccs.join(', ')}` : rules.mccs.join(', ')],
              ['Geography', rules.regions],
              ['Frequency', `${rules.frequency} per day`],
              ['Hold expiry', `${rules.stayMin} min`],
              ['partner_address', '0x4f2a…be19'],
              ['agent_address', 'did:web:ia.discountai.example#key-1 (Buying Partner)'],
            ]} />
            <p className="mt-4 text-xs text-ink-500">Each later edit creates a new contract version; matches already in flight keep the version active when they matched.</p>
          </Card>
          <Card title="Deploy and register" sub="Validated by the rule engine, then registered at the exchange">
            <ol className="flex flex-col gap-3">
              {[
                ['Validate the intent in the rule engine', check ? `${SOURCE[check.source]} · mandate_validation passed` : 'mandate_validation.dmn'],
                ['₹0 card check and security deposit to escrow', `Deposit ${inr(deposit)} posted (${depositBasis})`],
                ['Approve the instruction once with your passkey', `the card network purchase instruction ${mandate?.vicInstructionId ?? vicCalls.find((c) => c.r.action === 'instruction.create')?.r.response?.instructionId ?? ''} · the agent buys without asking until ${validUntil}`],
                ['Register as liquidity at the OME', mandate ? `Mandate ID ${mandate.id}` : 'Mandate ID issued'],
                ['Agent status', 'ACTIVE · listening for auctions'],
              ].map(([t, d], i) => (
                <li key={t} className="flex items-start gap-3">
                  <span className={cx('grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs', deploy > i ? 'bg-exchange-600 text-white' : deploy === i && deploy > 0 ? 'bg-amber-100 text-amber-800' : 'bg-ink-100 text-ink-500')}>
                    {deploy > i ? <Check size={13} /> : deploy === i && deploy > 0 ? <Loader2 size={13} className="animate-spin" /> : i + 1}
                  </span>
                  <div><p className="text-sm font-medium">{t}</p>{deploy > i && <p className="text-xs text-exchange-600">{d}</p>}</div>
                </li>
              ))}
            </ol>
            <div className="mt-4">
              <Callout tone="info" title={`About the ${inr(deposit)} security deposit`}>
                It is the larger of {depositPct}% of your {inr(rules.budgetTotal)} budget and the collateral on your largest order ({inr(rules.maxPerTx)}) as a new partner, as the exchange requires. It stays yours: the payment partner holds it in escrow, not DiscountAI, and your card is never pre-authorised instead. It is used only if your card declines an order your agent won ({slashNote} goes to the Guarantee Fund) or to recover a refund after you have been paid. When the mandate ends, whatever is left comes back to you.
              </Callout>
            </div>
            {check && <div className="mt-4"><RuleResultView r={check} /></div>}
            {customCheck && (
              <div className="mt-3">
                {customCheck.error
                  ? <Callout tone="warn" title="Custom rules not accepted">{customCheck.error} Fix them in step 3.</Callout>
                  : <Callout tone="good" title={`Custom rules compiled and run on ${customCheck.source === 'exchange' ? 'the exchange (FINOS Fluxnova DMN)' : customCheck.source === 'fluxnova' ? 'FINOS Fluxnova' : 'the edge DMN engine'}`}>
                      {custom.fields.length} fields · {custom.rules.length} rules · sample order: {customCheck.action?.toLowerCase()} ({customCheck.message}). They will run on every order in the auction.
                    </Callout>}
              </div>
            )}
            {vicCalls.length > 0 && <div className="mt-3 flex flex-col gap-2">{vicCalls.map((c) => <VicCallView key={c.title} title={c.title} r={c.r} />)}</div>}
            {check && !check.ok && <p className="mt-2 text-xs text-red-700">Fix the blocked rules in step 3, then deploy again.</p>}
            {mandate ? (
              <div className="mt-5 flex flex-wrap gap-2 items-center">
                <Badge tone="green" dot>{mandate.id} LIVE</Badge>
                <a className="btn-secondary" href={href('investor/mandates', mandate.id)}>Open mandate</a>
                <a className="btn-ghost" href={href('investor')}>Command center</a>
              </div>
            ) : (
              <div className="mt-5 flex justify-between">
                <button className="btn-ghost" onClick={() => setStep(2)} disabled={deploy > 0}><ArrowLeft size={15} /> Back</button>
                <button className="btn-primary" onClick={runDeploy} disabled={deploy > 0 || checking}>{checking ? <><Loader2 size={15} className="animate-spin" /> Checking rules…</> : deploy > 0 ? <><Loader2 size={15} className="animate-spin" /> Deploying…</> : 'Validate, sign and deploy'}</button>
              </div>
            )}
          </Card>
        </div>
      )}
    </>
  );
}

function CardConnect({ done, onDone }: { done: boolean; onDone: () => void }) {
  const [phase, setPhase] = useState<'start' | 'otp' | 'busy'>('start');
  const [otp, setOtp] = useState('');
  if (done) {
    return (
      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <p className="label mb-2">What DiscountAI keeps</p>
          <JsonView value={{ mandate_id: 'issued when you deploy', offer_BIN: '437551', bank: 'Bank X', max_discount: '20% up to ₹2,000', agent_token: 'held by our payment partner (Agent PA/PG), not DiscountAI', card_number: 'stays with Bank X and the card network' }} />
        </div>
        <div>
          <p className="label mb-2">Bank X offers · BIN 437551</p>
          <table className="w-full text-sm"><thead><tr><th className="th">Category</th><th className="th text-right">Discount</th><th className="th">Promo</th></tr></thead>
            <tbody>{DISCOUNT_RULES['437551'].map((r) => <tr key={r.category}><td className="td">{r.category}</td><td className="td text-right tabular">{r.pct}%</td><td className="td font-mono text-xs">{r.promo}</td></tr>)}</tbody></table>
          <p className="mt-2 text-xs text-ink-500">Card number and CVV stay with Bank X and the card network, and the agent token with our payment partner (RBI CoFT). DiscountAI keeps only the mandate ID, the offer BIN and the bank name.</p>
        </div>
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-ink-200 p-5 max-w-lg">
      <p className="font-medium">Connect Bank X with OAuth2</p>
      <p className="text-sm text-ink-600 mt-1">You'll approve access in Bank X's own login. DiscountAI receives an access token, the card BIN and the available limit.</p>
      {phase === 'start' && <button className="btn-primary mt-4" onClick={() => setPhase('otp')}>Continue to Bank X</button>}
      {phase !== 'start' && (
        <form className="mt-4" onSubmit={(e) => { e.preventDefault(); setPhase('busy'); setTimeout(onDone, 900); }}>
          <label htmlFor="otp" className="text-sm font-medium">One-time password from Bank X</label>
          <div className="mt-1.5 flex gap-2">
            <input id="otp" className="input w-40 tracking-[0.3em] font-mono" inputMode="numeric" maxLength={6} value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))} />
            <button className="btn-primary" disabled={otp.length !== 6 || phase === 'busy'}>{phase === 'busy' ? <Loader2 size={15} className="animate-spin" /> : 'Verify'}</button>
          </div>
          <p className="mt-1.5 text-xs text-amber-800">Prototype: any 6 digits work.</p>
        </form>
      )}
    </div>
  );
}

function UpiConnect({ done, onDone }: { done: boolean; onDone: () => void }) {
  const [app, setApp] = useState('UPI app A');
  const [amt, setAmt] = useState('100000');
  const [busy, setBusy] = useState(false);
  const [refs] = useState(() => ({ m: `UMN-${hex(4).toUpperCase()}`, b: `BLK-${hex(3).toUpperCase()}` }));
  if (done) return <Callout tone="good" title="Block fund mandate created">NPCI blocked ₹{Number(amt).toLocaleString('en-IN')} at your bank via {app}. Mandate ref <span className="font-mono">{refs.m}</span> · block ID <span className="font-mono">{refs.b}</span>. Funds stay in your account until an auction win.</Callout>;
  return (
    <div className="rounded-xl border border-ink-200 p-5 max-w-lg flex flex-col gap-3">
      <div><label htmlFor="tpap" className="text-sm font-medium">UPI app (TPAP)</label><select id="tpap" className="input mt-1.5" value={app} onChange={(e) => setApp(e.target.value)}><option>UPI app A</option><option>UPI app C</option><option>UPI app B</option></select></div>
      <div><label htmlFor="blk" className="text-sm font-medium">Amount to block (₹)</label><input id="blk" className="input mt-1.5 tabular" value={amt} onChange={(e) => setAmt(e.target.value.replace(/\D/g, ''))} /></div>
      <button className="btn-primary w-fit" disabled={busy || !Number(amt)} onClick={() => { setBusy(true); setTimeout(onDone, 1000); }}>{busy ? <><Loader2 size={15} className="animate-spin" /> Approve with UPI PIN in {app}…</> : `Create mandate in ${app}`}</button>
      <p className="text-xs text-ink-500">You enter your UPI PIN in {app}, never in DiscountAI.</p>
    </div>
  );
}

function ChainConnect({ done, onDone }: { done: boolean; onDone: () => void }) {
  const [chain, setChain] = useState('Base');
  const [busy, setBusy] = useState(false);
  const [tx] = useState(() => ({ hash: `0x${hex(6)}…`, block: 19283746 + Math.floor(Math.random() * 999) }));
  if (done) {
    return (
      <div className="grid gap-4 lg:grid-cols-2">
        <Callout tone="good" title="Mandate token minted">Spending cap approved on {chain}. Tx <span className="font-mono">{tx.hash}</span> · block {tx.block.toLocaleString()}.</Callout>
        <div>
          <p className="label mb-2">ZK solvency proof statements</p>
          <ul className="text-sm flex flex-col gap-1.5">
            {['balance ≥ required', 'asset_type = USDC', 'account_not_frozen = true', 'no_regulatory_holds = true'].map((s) => <li key={s} className="flex items-center gap-2"><Check size={14} className="text-exchange-600" /><span className="font-mono text-[12.5px]">{s}</span></li>)}
          </ul>
          <p className="mt-2 text-xs text-ink-500">~200-byte Groth16 proof; you bid without revealing your balance.</p>
        </div>
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-ink-200 p-5 max-w-lg flex flex-col gap-3">
      <div><label htmlFor="chain" className="text-sm font-medium">Finternet ledger</label><select id="chain" className="input mt-1.5" value={chain} onChange={(e) => setChain(e.target.value)}><option>Base</option><option>Polygon</option><option>Arbitrum</option></select></div>
      <p className="text-sm text-ink-600">Your wallet asks you to sign one transaction approving the mandate's spending cap. Funds move only into escrow when you win an auction.</p>
      <button className="btn-primary w-fit" disabled={busy} onClick={() => { setBusy(true); setTimeout(onDone, 1100); }}>{busy ? <><Loader2 size={15} className="animate-spin" /> Waiting for wallet signature…</> : 'Sign spending-cap approval'}</button>
    </div>
  );
}
