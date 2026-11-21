import { LIVE } from '../../api/exchange';
import { useEffect, useRef, useState } from 'react';
import { ArrowRight, BadgePercent, Bot, Check, CreditCard, ExternalLink, Fingerprint, Loader2, Plus, Radio, Send, ShieldCheck, Store, Users, Wrench } from 'lucide-react';
import { MERCHANTS, SCENARIO, SPLIT } from '../../sim/data';
import { useEngine } from '../../sim/engine';
import { inr, rid } from '../../sim/format';
import type { Outcome } from '../../sim/types';
import { Badge, Callout, Card, JsonView, KV, PageHeader, StatusBadge, cx } from '../../components/ui';
import { CITIES, FALLBACK_PLATFORMS, STORE_SEARCH, creditsFrom, money, productsFrom, qcCall, qcStatus, type LiveProduct, type QcBudget, type QcResult, type QcStatus } from '../../sim/quickcommerce';
import { RequestTracker } from './RequestTracker';
import { evaluate, type RuleResult } from '../../sim/rules';
import { RuleResultView } from '../../components/RuleResultView';
import { RankingInfo } from '../../components/RankingInfo';
import { ConfirmationSettings } from './ConfirmationSettings';
import { PLATFORM_FEE_PCT, TRADER, traderName } from '../../legal';

type Live = { enabled: boolean; platform: string; cityId: string };
type LiveRun = {
  query: string; platform: string; city: string; args: Record<string, unknown>; products: LiveProduct[];
  source?: 'cache' | 'similar' | 'live'; ageMin?: number; similarTo?: string; error?: string; needsPaid?: boolean; busy?: boolean;
};
const age = (min?: number) => (min === undefined || min < 1 ? 'just now' : min < 60 ? `${min} min old` : `${Math.round(min / 60)} h old`);

type Msg =
  | { kind: 'user'; text: string }
  | { kind: 'agent'; text: string }
  | { kind: 'tool'; fn: string; args: string; result: string; ok?: boolean };

type Stage = 'idle' | 'discovering' | 'options' | 'configure' | 'tracking' | 'optionA' | 'optionB';

const split = SPLIT;
const optionAPrice = SCENARIO.list * (1 - SCENARIO.buyerCard.cashbackPct / 100);

export default function BuyerAgent() {
  const { state, submitRequest } = useEngine();
  const [msgs, setMsgs] = useState<Msg[]>([
    { kind: 'agent', text: "Hi, I'm ShopBot. Tell me what you want to buy. I'll compare merchants, check every card and bank offer, and if the best deal needs a card you don't hold, a buying partner's agent on the DiscountAI exchange can buy it with their card and ship it to you." },
  ]);
  const [input, setInput] = useState('');
  const [stage, setStage] = useState<Stage>('idle');
  const [reqId, setReqId] = useState<string>();
  const scroller = useRef<HTMLDivElement>(null);

  // Live market data through the Quick Commerce MCP (free plan), via our server-side proxy.
  const [qc, setQc] = useState<QcStatus | null | undefined>(undefined); // undefined = checking, null = unavailable
  const [credits, setCredits] = useState<number>();
  const [live, setLive] = useState<Live>({ enabled: false, platform: 'Marketplace B', cityId: 'blr' });
  const [liveRun, setLiveRun] = useState<LiveRun>();
  const [budget, setBudget] = useState<QcBudget>();

  useEffect(() => {
    let alive = true;
    qcStatus().then(async (s) => {
      if (!alive) return;
      setQc(s);
      if (s?.budget) setBudget(s.budget);
      if (s?.configured) {
        setLive((l) => ({ ...l, enabled: true }));
        const c = await qcCall('check_credits'); // free, and cached on the server for 10 minutes
        if (alive && c.ok) setCredits(creditsFrom(c.data));
        if (alive && c.budget) setBudget(c.budget);
      }
    });
    return () => { alive = false; };
  }, []);

  /** Turns a proxy answer into the results table and a chat line. */
  function showResult(base: Omit<LiveRun, 'products'>, res: QcResult) {
    if (res.budget) { setBudget(res.budget); if (res.budget.credits !== null) setCredits(res.budget.credits); }
    const left = res.ok ? creditsFrom(res.data) : undefined;
    if (left !== undefined) setCredits(left);
    const products = res.ok ? productsFrom(res.data, base.platform) : [];
    setLiveRun({ ...base, products, source: res.source, ageMin: res.ageMin, similarTo: res.similarTo, needsPaid: res.needsPaid, error: res.ok || res.needsPaid ? undefined : res.error });
    const where = `"${base.query}", ${base.platform}, ${base.city}`;
    if (res.needsPaid) {
      push({ kind: 'tool', fn: 'search_products', args: where, result: `Not in the free cache. A live search costs 1 credit, so I'm waiting for your OK in Live market data. Free store links are there too.` });
      return;
    }
    if (!res.ok) { push({ kind: 'tool', fn: 'search_products', args: where, result: res.error ?? 'Live search failed', ok: false }); return; }
    const best = products.find((p) => p.available) ?? products[0];
    const how = res.source === 'live' ? '1 credit' : res.source === 'similar' ? `0 credits, reused from “${res.similarTo}”` : `0 credits, from cache (${age(res.ageMin)})`;
    push({
      kind: 'tool', fn: 'search_products', args: where,
      result: products.length ? `${products.length} results · ${how} · top match ${best.name} at ${money(best.price, best.currency)}` : `No results on ${base.platform} (${how})`,
      ok: products.length > 0,
    });
  }

  async function runPaidSearch() {
    if (!liveRun?.needsPaid || liveRun.busy) return;
    setLiveRun({ ...liveRun, busy: true });
    const res = await qcCall('search_products', liveRun.args, true);
    showResult(liveRun, res);
  }

  useEffect(() => { scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' }); }, [msgs]);

  async function liveSearch(text: string) {
    const city = CITIES.find((c) => c.id === live.cityId) ?? CITIES[0];
    const args = { query: text, platform: live.platform, lat: city.lat, lon: city.lon, pincode: city.pincode };
    // Free lookup only (exact or similar cached result). A paid search waits for an explicit click.
    const res = await qcCall('search_products', args, false);
    showResult({ query: text, platform: live.platform, city: city.name, args }, res);
  }

  const delay = (ms: number) => new Promise((r) => setTimeout(r, state.speed === 'presenter' ? ms : state.speed === 'normal' ? ms * 0.35 : 0));
  const push = (m: Msg) => setMsgs((x) => [...x, m]);

  async function discover(text: string) {
    if (stage === 'discovering') return;
    push({ kind: 'user', text });
    setInput('');
    setStage('discovering');
    if (live.enabled && qc?.configured) {
      await liveSearch(text);
      await delay(400);
    } else {
      await delay(500);
    }
    push({ kind: 'tool', fn: 'search_product', args: `"${SCENARIO.product}"${live.enabled && qc?.configured ? ' · demo merchants' : ''}`, result: `${MERCHANTS.length} merchants · ${inr(SCENARIO.list)} at Electronics Store A, Marketplace A, Marketplace B` });
    await delay(800);
    push({ kind: 'tool', fn: 'query_discount_promotions', args: 'store-a', result: `${SCENARIO.offerName} (${inr(split.discount)}) · BIN ${SCENARIO.bin} · Offer-Liquidity Programme (V1)` });
    await delay(800);
    push({ kind: 'tool', fn: 'check_buyer_eligibility', args: '"Bank X card"', result: 'Not eligible · no Bank X card in wallet', ok: false });
    await delay(700);
    push({ kind: 'agent', text: `Every big store sells it at ${inr(SCENARIO.list)}. Electronics Store A has a Bank X offer that takes it to ${inr(split.A)}, but you don't hold a Bank X card. A buying partner with that card can buy it and have it shipped to you, for about ${inr(split.B)}. You have three ways to pay.` });
    setStage('options');
  }

  const req = state.requests.find((r) => r.id === reqId);

  return (
    <>
      <PageHeader eyebrow="ShopBot AI" title="ShopBot AI shopping agent"
        desc="MCP discovery and gap analysis, then an AP2 intent to the exchange when the best discount needs a credential you don't hold." />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,420px)_minmax(0,1fr)]">
        {/* Conversation */}
        <section className="card flex flex-col min-h-[560px] xl:h-[calc(100vh-210px)] xl:sticky xl:top-20">
          <header className="flex items-center gap-2.5 px-4 h-12 border-b border-ink-100">
            <span className="grid h-7 w-7 place-items-center rounded-lg bg-buyer-100 text-buyer-600"><Bot size={16} /></span>
            <div className="leading-tight"><p className="font-semibold text-[13.5px]">ShopBot</p><p className="text-[11px] text-ink-500">Buyer agent · AP2 / ACP / MCP</p></div>
            <Badge tone="green" dot>Online</Badge>
          </header>
          <div ref={scroller} className="flex-1 overflow-y-auto px-4 py-4 flex flex-col gap-3">
            {msgs.map((m, i) => <Message key={i} m={m} />)}
            {stage === 'discovering' && <p className="flex items-center gap-2 text-xs text-ink-500"><Loader2 size={13} className="animate-spin" /> Working through MCP tools…</p>}
          </div>
          <form className="border-t border-ink-100 p-3 flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); if (input.trim()) discover(input.trim()); }}>
            {stage === 'idle' && (
              <button type="button" className="text-left text-[12.5px] rounded-lg border border-dashed border-buyer-600/40 bg-buyer-50 px-3 py-2 text-buyer-600 hover:bg-buyer-100"
                onClick={() => discover(SCENARIO.query)}>Try: “{SCENARIO.query}”</button>
            )}
            <div className="flex gap-2">
              <label htmlFor="buyer-input" className="sr-only">Message ShopBot</label>
              <input id="buyer-input" className="input" placeholder="What do you want to buy?" value={input} onChange={(e) => setInput(e.target.value)} disabled={stage !== 'idle'} />
              <button className="btn-primary px-3" disabled={stage !== 'idle' || !input.trim()} aria-label="Send"><Send size={15} /></button>
            </div>
          </form>
        </section>

        {/* Workspace */}
        <div className="flex flex-col gap-5 min-w-0">
          <LiveMarket qc={qc} credits={credits} budget={budget} live={live} setLive={setLive} run={liveRun} locked={stage === 'discovering'} onPaid={runPaidSearch} />
          {stage === 'idle' && <IdleWorkspace />}
          {stage !== 'idle' && <DiscoveryCard ready={stage !== 'discovering'} />}
          {stage === 'options' && <Options onPick={(o) => setStage(o)} />}
          {stage === 'configure' && (
            <Configure onBack={() => setStage('options')} onSubmit={(o) => {
              const id = submitRequest(o);
              setReqId(id);
              setStage('tracking');
              push({ kind: 'agent', text: `Request sent to the exchange. I've signed it (Proof-of-Intent). Nothing is charged until a buying partner matches; you'll hear "confirmed" only once the merchant approves the buying partner's card.` });
            }} />
          )}
          {stage === 'optionA' && <SimpleCheckout title={`Pay ${inr(optionAPrice)} with your ${SCENARIO.buyerCard.name}`} detail={`${SCENARIO.buyerCard.cashbackPct}% cashback on your saved card · no marketplace fee`} onBack={() => setStage('options')} />}
          {stage === 'optionB' && <SimpleCheckout title={`Add a Bank X card and pay ${inr(split.A)}`} detail="Your bank's app opens to approve network tokenisation (RBI CoFT). Card number and CVV never pass through DiscountAI." onBack={() => setStage('options')} />}
          {stage === 'tracking' && req && (
            <Card title={<span className="flex items-center gap-2">Marketplace request <span className="font-mono text-xs text-ink-500">{req.id}</span></span>} right={<StatusBadge status={req.status} />}>
              <RequestTracker req={req} />
              <div className="mt-5 flex flex-wrap gap-2">
                <button className="btn-secondary" onClick={() => { setStage('idle'); setReqId(undefined); setLiveRun(undefined); setMsgs((m) => m.slice(0, 1)); }}>New search</button>
              </div>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}

function Message({ m }: { m: Msg }) {
  if (m.kind === 'user') return <div className="rise self-end max-w-[85%] rounded-2xl rounded-br-md bg-ink-900 text-white px-3.5 py-2 text-[13.5px]">{m.text}</div>;
  if (m.kind === 'agent') return <div className="rise self-start max-w-[90%] rounded-2xl rounded-bl-md bg-ink-100 px-3.5 py-2 text-[13.5px] text-ink-800">{m.text}</div>;
  return (
    <div className="rise self-start w-full rounded-lg border border-ink-200 bg-white text-[12.5px]">
      <div className="flex items-center gap-1.5 px-3 py-1.5 border-b border-ink-100 text-ink-500"><Wrench size={12} /> MCP tool call</div>
      <div className="px-3 py-2 font-mono text-[12px] text-ink-800">{m.fn}(<span className="text-buyer-600">{m.args}</span>)</div>
      <div className={cx('px-3 pb-2 flex items-start gap-1.5', m.ok === false ? 'text-red-700' : 'text-exchange-600')}>
        <span aria-hidden>→</span><span>{m.result}</span>
      </div>
    </div>
  );
}

function LiveMarket({ qc, credits, budget, live, setLive, run, locked, onPaid }: {
  qc: QcStatus | null | undefined; credits?: number; budget?: QcBudget; live: Live; setLive: (f: (l: Live) => Live) => void; run?: LiveRun; locked: boolean; onPaid: () => void;
}) {
  const platforms = qc?.searchPlatforms?.length ? qc.searchPlatforms.filter((p) => FALLBACK_PLATFORMS.includes(p)) : FALLBACK_PLATFORMS;
  const status = qc === undefined
    ? <Badge tone="gray" dot>Checking</Badge>
    : qc === null ? <Badge tone="gray">Not available here</Badge>
    : qc.configured ? <Badge tone="green" dot>Connected · free plan</Badge>
    : <Badge tone="amber">API key needed</Badge>;
  const shown = run?.products.slice(0, 6) ?? [];
  return (
    <Card title={<span className="flex items-center gap-2"><Radio size={16} className="text-buyer-600" /> Live market data</span>}
      sub="Quick Commerce MCP · real prices from Indian stores, shown next to the demo scenario" right={status}>
      {qc === null && (
        <p className="text-[13px] text-ink-600">The live data service runs on the deployed site and in <span className="font-mono">npm run dev</span>. This copy uses the demo merchants only.</p>
      )}
      {qc && !qc.configured && (
        <Callout tone="warn" title="Add your free Quick Commerce key">
          Sign up free at a third-party price API (100 credits, no card), copy the key from the dashboard, and set <span className="font-mono">QUICKCOMMERCE_API_KEY</span> in the site's
          environment variables (or in <span className="font-mono">.env.local</span> for local runs). ShopBot uses the demo merchants until then.
        </Callout>
      )}
      {qc?.configured && (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" className="accent-brand-600" checked={live.enabled} disabled={locked} onChange={(e) => setLive((l) => ({ ...l, enabled: e.target.checked }))} />
              Use live prices in ShopBot
            </label>
            <div className="flex flex-col gap-1">
              <label htmlFor="qc-platform" className="label">Store</label>
              <select id="qc-platform" className="input w-40" value={live.platform} disabled={locked || !live.enabled} onChange={(e) => setLive((l) => ({ ...l, platform: e.target.value }))}>
                {platforms.map((p) => <option key={p}>{p}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor="qc-city" className="label">Deliver to</label>
              <select id="qc-city" className="input w-36" value={live.cityId} disabled={locked || !live.enabled} onChange={(e) => setLive((l) => ({ ...l, cityId: e.target.value }))}>
                {CITIES.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
            <p className="ml-auto text-xs text-ink-500 text-right">
              {credits !== undefined && <><span className="tabular font-medium text-ink-800">{credits}</span> credits left · </>}
              {budget && <><span className="tabular font-medium text-ink-800">{Math.min(budget.siteLeftToday, budget.visitorLeftToday)}</span> live search{Math.min(budget.siteLeftToday, budget.visitorLeftToday) === 1 ? '' : 'es'} left for you today · </>}
              saved results are free for 24 h
            </p>
          </div>
          {run?.error && <Callout tone="warn" title="Live search didn't work">{run.error}</Callout>}
          {run?.needsPaid && (
            <div className="rounded-lg border border-ink-200 bg-ink-50 p-4 flex flex-col gap-3">
              <p className="text-[13.5px] text-ink-800">
                <b>“{run.query}”</b> on {run.platform} ({run.city}) isn't saved yet. A live search costs <b>1 credit</b> and is then free for everyone for 24 hours.
              </p>
              {budget && !budget.paidAllowed && budget.reason && <p className="text-[13px] text-amber-800">{budget.reason}</p>}
              <div className="flex flex-wrap items-center gap-2">
                <button className="btn-primary" disabled={run.busy || (budget ? !budget.paidAllowed : false)} onClick={onPaid}>
                  {run.busy ? <><Loader2 size={14} className="animate-spin" /> Searching…</> : <>Search live · 1 credit</>}
                </button>
                <span className="text-xs text-ink-500">or check prices for free on the store's own site:</span>
              </div>
            </div>
          )}
          {run && (
            <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-500">
              <span>Free, no credits:</span>
              {Object.entries(STORE_SEARCH).map(([store, url]) => (
                <a key={store} className="inline-flex items-center gap-1 text-brand-700 hover:underline" href={url(run.query)} target="_blank" rel="noopener noreferrer">{store} <ExternalLink size={11} /></a>
              ))}
            </p>
          )}
          {run && !run.error && !run.needsPaid && (
            shown.length === 0
              ? <p className="text-[13px] text-ink-600">No live results for “{run.query}” on {run.platform}. Try another store; Marketplace B and Marketplace A carry electronics, Quick commerce A and Quick commerce B carry groceries.</p>
              : (
                <div className="overflow-x-auto -mx-1">
                  <table className="w-full text-sm">
                    <caption className="text-left text-xs text-ink-500 pb-2 px-1">“{run.query}” on {run.platform}, delivering to {run.city} · {run.source === 'live' ? 'live, 1 credit' : run.source === 'similar' ? `reused from the saved “${run.similarTo}” search, 0 credits` : `saved result (${age(run.ageMin)}), 0 credits`}</caption>
                    <thead><tr><th className="th">Product</th><th className="th text-right">Price</th><th className="th">Stock</th><th className="th"><span className="sr-only">Link</span></th></tr></thead>
                    <tbody>
                      {shown.map((p) => (
                        <tr key={p.id}>
                          <td className="td">
                            <span className="font-medium text-ink-900">{p.name}</span>
                            <span className="block text-xs text-ink-500">{[p.brand, p.quantity, p.rating ? `★ ${p.rating.toFixed(1)}` : null].filter(Boolean).join(' · ')}</span>
                          </td>
                          <td className="td text-right tabular whitespace-nowrap">
                            {money(p.price, p.currency)}
                            {p.mrp && p.mrp > p.price && <span className="block text-xs text-ink-400 line-through">{money(p.mrp, p.currency)}</span>}
                          </td>
                          <td className="td text-ink-600 whitespace-nowrap">{p.available ? (p.sla ? `In stock · ${p.sla}` : 'In stock') : 'Out of stock'}</td>
                          <td className="td">{p.deeplink && <a className="inline-flex items-center gap-1 text-brand-700 hover:underline whitespace-nowrap" href={p.deeplink} target="_blank" rel="noopener noreferrer">Open <ExternalLink size={12} /></a>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )
          )}
          {!run && <p className="text-[13px] text-ink-600">Ask ShopBot for anything (for example “Laptop Air M3” or “Amul butter”). ShopBot first looks for a saved result (free). A new live search only runs when you click <b>Search live</b>, and each one is shared with everyone for 24 hours.</p>}
        </div>
      )}
    </Card>
  );
}

function IdleWorkspace() {
  return (
    <Card title="How the buyer agent works" sub="MCP discovery · AP2 intent · status and settlement">
      <ol className="grid gap-3 sm:grid-cols-2">
        {[
          ['Discover', 'search_product() across Electronics Store A, Marketplace A, Marketplace B and local retailers.'],
          ['Find the offer', 'query_discount_promotions() returns live bank and card promotions.'],
          ['Check your wallet', 'check_buyer_eligibility() tests your saved cards. A miss creates a Gap object.'],
          ['A buying partner buys it and ships it to you', 'Your signed request goes to the exchange; buying partner agents bid for 50 ms, and the winner buys it in the buying partner\'s own name with their card and ships it to you.'],
        ].map(([t, d], i) => (
          <li key={t} className="flex gap-3 rounded-lg bg-ink-50 p-3">
            <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-buyer-100 text-buyer-600 text-xs font-semibold">{i + 1}</span>
            <div><p className="font-medium text-ink-900">{t}</p><p className="text-ink-600 text-[13px]">{d}</p></div>
          </li>
        ))}
      </ol>
    </Card>
  );
}

function DiscoveryCard({ ready }: { ready: boolean }) {
  return (
    <Card title={<span className="flex items-center gap-2"><Store size={16} className="text-buyer-600" /> {SCENARIO.product}</span>} sub={SCENARIO.productDetail}
      right={ready ? <Badge tone="orange">Gap detected</Badge> : <Badge tone="gray" dot>Searching</Badge>}>
      <div className="grid gap-5 2xl:grid-cols-[minmax(0,1fr)_minmax(0,300px)]">
        <div className="overflow-x-auto -mx-1">
          <table className="w-full text-sm">
            <thead><tr><th className="th">Merchant</th><th className="th text-right">Price</th><th className="th">Availability</th><th className="th">Bank X offer</th></tr></thead>
            <tbody>
              {MERCHANTS.map((m) => (
                <tr key={m.id}>
                  <td className="td font-medium">{m.name}</td>
                  <td className="td text-right tabular">{inr(m.price)}</td>
                  <td className="td text-ink-600">{m.stock}</td>
                  <td className="td">{m.id === 'store-a' ? <Badge tone="green">20% · {inr(split.A)}</Badge> : m.promo ? <span className="text-ink-500 text-xs">10% cashback</span> : <span className="text-ink-400 text-xs">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <RankingInfo className="mt-3 mx-1" />
        </div>
        <div className={cx('transition-opacity', ready ? 'opacity-100' : 'opacity-40')}>
          <p className="label mb-2">Gap object</p>
          <JsonView value={{ need: 'Bank X instrument', bin: SCENARIO.bin, offer: SCENARIO.offerName, A: split.A, buy_request: { product_id: 'prod-001', acceptable_merchants: ['M1', 'M2'], max_price: optionAPrice, currency: 'INR', delivery: 'buyer address (hashed in POI)', deadline: '30s' } }} />
        </div>
      </div>
    </Card>
  );
}

function Options({ onPick }: { onPick: (s: Stage) => void }) {
  const opts = [
    { id: 'optionA' as Stage, icon: CreditCard, title: 'A · Use my saved card', price: inr(optionAPrice), note: `${SCENARIO.buyerCard.name} ••${SCENARIO.buyerCard.last4} · ${SCENARIO.buyerCard.cashbackPct}% cashback`, cta: 'Pay with saved card' },
    { id: 'optionB' as Stage, icon: Plus, title: 'B · I have this card, add it', price: inr(split.A), note: 'Add Bank X via your bank (network token)', cta: 'Add card' },
    { id: 'configure' as Stage, icon: Users, title: 'C · A buying partner buys it and ships it to me', price: `≈ ${inr(split.B)}`, note: `${inr(split.A)} + ${inr(split.Y)} buying partner yield + ${inr(split.F)} fee · saves ${inr(split.buyerSaves)} · ships to you`, cta: 'Find a buying partner', best: true },
  ];
  return (
    <Card title="Choose how to pay" sub="Three options from the gap analysis">
      <div className="grid gap-3 md:grid-cols-3">
        {opts.map((o) => (
          <div key={o.title} className={cx('relative flex flex-col rounded-xl border p-4', o.best ? 'border-brand-500 bg-brand-50/50 ring-1 ring-brand-500' : 'border-ink-200')}>
            {o.best && <span className="absolute -top-2.5 left-4"><Badge tone="brand">Lowest price</Badge></span>}
            <o.icon size={18} className="text-ink-500" />
            <p className="mt-2 font-medium text-ink-900">{o.title}</p>
            <p className="mt-1 text-2xl font-semibold font-display tabular">{o.price}</p>
            <p className="mt-1 text-[13px] text-ink-600 flex-1">{o.note}</p>
            <button className={cx('mt-4', o.best ? 'btn-primary' : 'btn-secondary')} onClick={() => onPick(o.id)}>{o.cta} <ArrowRight size={14} /></button>
          </div>
        ))}
      </div>
    </Card>
  );
}

function Configure({ onBack, onSubmit }: { onBack: () => void; onSubmit: (o: { maxPrice: number; expiry: string; outcome: Outcome }) => void }) {
  const [hh, setHh] = useState('00');
  const [mm, setMm] = useState('30');
  const [capMode, setCapMode] = useState<'auto' | 'manual'>('auto');
  const [manual, setManual] = useState(String(SPLIT.B));
  const [card, setCard] = useState<'none' | 'auth' | 'done'>('none');
  const [cardRef, setCardRef] = useState('');
  // No pre-ticked boxes: every consent starts unticked and only "agent" and "hold" are required.
  const [consents, setConsents] = useState({ discovery: false, agent: false, hold: false });
  const [outcome, setOutcome] = useState<Outcome>('success');
  const minCap = SPLIT.A + SPLIT.F; // below A + F no buying partner can earn anything
  const maxCap = optionAPrice;      // above this the buyer's own card is cheaper
  const manualNum = Number(manual);
  const capValid = capMode === 'auto' || (manualNum >= minCap && manualNum <= maxCap);
  const cap = capMode === 'auto' ? maxCap : manualNum;
  const timeValid = Number(hh) * 60 + Number(mm) > 0;
  const { state: engineState } = useEngine();
  const ready = capValid && timeValid && card === 'done' && consents.agent && consents.hold && !!engineState.buyerPrefs;
  const [check, setCheck] = useState<RuleResult>();
  const [checking, setChecking] = useState(false);
  // Buyer intent (POI) eligibility runs in the DMN rule engine before the request is broadcast.
  const send = async () => {
    // Live: the exchange checks the same DMN rules on the intent and answers with its findings; no browser pre-check.
    if (LIVE) { onSubmit({ maxPrice: cap, expiry: `${hh.padStart(2, '0')}:${mm.padStart(2, '0')}`, outcome }); return; }
    setChecking(true);
    const r = await evaluate('buyer_intent_eligibility', {
      riskScore: 850 + Math.floor(Math.random() * 60), maxPrice: cap, minViable: SPLIT.A + SPLIT.F,
      listPrice: SCENARIO.list, offerSanctioned: true,
    });
    setCheck(r); setChecking(false);
    if (r.ok) setTimeout(() => onSubmit({ maxPrice: cap, expiry: `${hh.padStart(2, '0')}:${mm.padStart(2, '0')}`, outcome }), 900);
  };

  return (
    <Card title="Have a buying partner's agent buy it and ship it to you" sub="Max price, your card, and consent" right={<button className="btn-ghost text-xs" onClick={onBack}>Back to options</button>}>
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="flex flex-col gap-5">
          <fieldset>
            <legend className="font-medium text-ink-900">1. How long should I look for a buying partner?</legend>
            <div className="mt-2 flex items-center gap-2">
              <label className="sr-only" htmlFor="exp-h">Hours</label>
              <input id="exp-h" className="input w-16 text-center tabular" value={hh} inputMode="numeric" maxLength={2} onChange={(e) => setHh(e.target.value.replace(/\D/g, ''))} />
              <span className="text-ink-400">:</span>
              <label className="sr-only" htmlFor="exp-m">Minutes</label>
              <input id="exp-m" className="input w-16 text-center tabular" value={mm} inputMode="numeric" maxLength={2} onChange={(e) => setMm(e.target.value.replace(/\D/g, ''))} />
              <span className="text-xs text-ink-500">HH : MM</span>
            </div>
            <p className="mt-1.5 text-xs text-ink-500">Each auction closes in 50 ms and quotes within 30 s. If nothing matches, the request just expires: nothing is held until there's a match.</p>
            {!timeValid && <p className="mt-1 text-xs text-red-700">Set a wait time above zero.</p>}
          </fieldset>

          <fieldset>
            <legend className="font-medium text-ink-900">2. Maximum price you'll pay (max_price)</legend>
            <div className="mt-2 flex flex-col gap-2">
              <label className={cx('flex items-start gap-3 rounded-lg border p-3 cursor-pointer', capMode === 'auto' ? 'border-brand-500 bg-brand-50/40' : 'border-ink-200')}>
                <input type="radio" name="cap" className="mt-1 accent-brand-600" checked={capMode === 'auto'} onChange={() => setCapMode('auto')} />
                <span><span className="font-medium">Up to {inr(maxCap)}</span><span className="block text-xs text-ink-500">What your own {SCENARIO.buyerCard.name} would cost. The best buying partner quote is expected at {inr(SPLIT.B)}.</span></span>
              </label>
              <label className={cx('flex items-start gap-3 rounded-lg border p-3 cursor-pointer', capMode === 'manual' ? 'border-brand-500 bg-brand-50/40' : 'border-ink-200')}>
                <input type="radio" name="cap" className="mt-1 accent-brand-600" checked={capMode === 'manual'} onChange={() => setCapMode('manual')} />
                <span className="flex-1">
                  <span className="font-medium">Set my own limit</span>
                  <span className="block text-xs text-ink-500">Between {inr(minCap)} (card price plus fee) and {inr(maxCap)}.</span>
                  {capMode === 'manual' && (
                    <span className="mt-2 flex items-center gap-2">
                      <label htmlFor="cap-manual" className="sr-only">Maximum price</label>
                      <span className="text-ink-500">₹</span><input id="cap-manual" className="input w-32 tabular" inputMode="numeric" value={manual} onChange={(e) => setManual(e.target.value.replace(/\D/g, ''))} />
                    </span>
                  )}
                  {capMode === 'manual' && !capValid && <span className="block mt-1 text-xs text-red-700">Enter an amount between {inr(minCap)} and {inr(maxCap)}.</span>}
                </span>
              </label>
            </div>
          </fieldset>
        </div>

        <div className="flex flex-col gap-5">
          <fieldset>
            <legend className="font-medium text-ink-900">3. Your card (network token)</legend>
            <div className="mt-2 rounded-lg border border-ink-200 p-4">
              <KV rows={[
                ['Hold placed', 'Only after a match'],
                ['Hold amount', <b key="b">the quoted B, about {inr(SPLIT.B)}</b>],
                ['Captured', 'When the merchant ships'],
                ['Seller (trader) you buy from', `${traderName()}${TRADER.address ? `, ${TRADER.address}` : ''} · MCC 5262`],
              ]} />
              <p className="mt-2 text-xs text-ink-500">You pay DiscountAI, never the buying partner. The buying partner buys at {SCENARIO.merchant} with their own card and it ships to you, so you keep card-scheme chargeback protection.</p>
              <div className="mt-4">
                {card === 'done' ? (
                  <Callout tone="good" title="Card tokenised">{SCENARIO.buyerCard.name} ••{SCENARIO.buyerCard.last4} · token held by our payment partner, reference <span className="font-mono">{cardRef}</span></Callout>
                ) : (
                  <button className="btn-secondary w-full" disabled={card === 'auth'} onClick={() => { setCard('auth'); setTimeout(() => { setCardRef(rid('tok_agpa_', 6)); setCard('done'); }, 1100); }}>
                    {card === 'auth' ? <><Loader2 size={15} className="animate-spin" /> Waiting for passkey…</> : <><Fingerprint size={15} /> Use my saved card (passkey)</>}
                  </button>
                )}
              </div>
            </div>
          </fieldset>

          <fieldset>
            <legend className="font-medium text-ink-900">4. Consent <span className="text-xs font-normal text-ink-500">(DPDP Act 2023 · granular)</span></legend>
            <div className="mt-2 flex flex-col gap-1.5 text-sm">
              {([['discovery', 'Optional: use my request for market discovery'], ['agent', 'Let DiscountAI arrange it: a buying partner buys this item in their own name and it ships to my address'], ['hold', 'Hold the quoted amount on my card and capture it on shipment']] as const).map(([k, t]) => (
                <label key={k} className="flex items-start gap-2.5"><input type="checkbox" className="mt-0.5 accent-brand-600" checked={consents[k]} onChange={(e) => setConsents({ ...consents, [k]: e.target.checked })} />{t}</label>
              ))}
            </div>
          </fieldset>

          {/* The live exchange decides the outcome; the picker only steers the offline simulation. */}
          {!LIVE && <div className="rounded-lg border border-dashed border-amber-300 bg-amber-50/60 p-3">
            <label htmlFor="outcome" className="text-xs font-medium text-amber-900">Prototype control · outcome to simulate</label>
            <select id="outcome" className="input mt-1.5" value={outcome} onChange={(e) => setOutcome(e.target.value as Outcome)}>
              <option value="success">Success · winning buying partner's card approved</option>
              <option value="investor-decline">Winner declines · silent failover to the next bidder</option>
              <option value="all-decline">All bidders decline · Guarantee Fund backstop</option>
              <option value="no-match">Timed out · no eligible buying partner bid</option>
            </select>
          </div>}
        </div>
      </div>
      <div className="mt-6">
        <Callout tone="info" title="What DiscountAI is paid on this order">
          DiscountAI receives a platform fee of {inr(SPLIT.F)} ({PLATFORM_FEE_PCT}% of the {inr(SCENARIO.list)} list price), and the buying partner earns a yield of about {inr(SPLIT.Y)}. Both are already inside the price shown. Buying partner bids are ranked partly by the yield they ask, so this order is never placed without your confirmation: you confirm it by pressing “Send to the exchange”.
        </Callout>
        <RankingInfo className="mt-3" />
        <details className="mt-3 rounded-lg border border-ink-200 bg-white px-3 py-2 text-[13px]">
          <summary className="cursor-pointer font-medium text-ink-900">Pre-contract terms (machine-readable, for your own agent)</summary>
          <div className="mt-2"><JsonView value={{
            '@context': 'https://schema.org', '@type': 'Offer', name: `${SCENARIO.product}, bought by a buying partner's agent and shipped to you`,
            seller: { '@type': 'Organization', name: traderName(), ...(TRADER.address ? { address: TRADER.address } : {}), url: TRADER.website },
            itemOffered: { '@type': 'Product', name: SCENARIO.product }, price: SPLIT.B, priceCurrency: 'INR',
            priceSpecification: [
              { '@type': 'UnitPriceSpecification', name: 'Card price at the merchant (A)', price: SPLIT.A, priceCurrency: 'INR' },
              { '@type': 'UnitPriceSpecification', name: 'Buying partner yield (Y)', price: SPLIT.Y, priceCurrency: 'INR' },
              { '@type': 'UnitPriceSpecification', name: 'DiscountAI platform fee (F), paid for proposing this order', price: SPLIT.F, priceCurrency: 'INR' },
            ],
            shippingDetails: { '@type': 'OfferShippingDetails', shippingDestination: 'your address (hashed in the request)' },
            hasMerchantReturnPolicy: { '@type': 'MerchantReturnPolicy', returnPolicyCategory: 'https://schema.org/MerchantReturnFiniteReturnWindow', merchantReturnDays: 7, refundType: 'https://schema.org/FullRefund' },
            paymentTerms: 'Card hold of B after a match; captured when the item ships',
          }} /></div>
        </details>
        <div className="mt-3"><ConfirmationSettings /></div>
      </div>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-ink-100 pt-4">
        <p className="text-xs text-ink-500 flex items-center gap-1.5"><ShieldCheck size={14} /> ShopBot signs your request (POI) and the L3 risk engine checks it before broadcast.</p>
        <button className="btn-primary" disabled={!ready || checking || !!check?.ok} onClick={send}>
          {checking ? <><Loader2 size={15} className="animate-spin" /> Checking rules…</> : <>Send to the exchange <ArrowRight size={15} /></>}
        </button>
      </div>
      {check && <div className="mt-3"><RuleResultView r={check} /></div>}
      {!ready && <p className="mt-2 text-right text-xs text-ink-500">{engineState.buyerPrefs ? 'Confirm your card and tick both required consents to continue.' : 'Choose when ShopBot should ask you to confirm orders, confirm your card and tick both required consents to continue.'}</p>}
    </Card>
  );
}

function SimpleCheckout({ title, detail, onBack }: { title: string; detail: string; onBack: () => void }) {
  const [done, setDone] = useState(false);
  return (
    <Card title={title} right={<button className="btn-ghost text-xs" onClick={onBack}>Back to options</button>}>
      {done ? (
        <Callout tone="good" title="Order placed">Paid directly with your own credential. No exchange request was needed, so there is no proof chain for this order.</Callout>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-4">
          <p className="text-ink-600 max-w-xl flex items-start gap-2"><BadgePercent size={16} className="mt-0.5 text-ink-400" />{detail}</p>
          <button className="btn-primary" onClick={() => setDone(true)}><Check size={15} /> Confirm and pay</button>
        </div>
      )}
    </Card>
  );
}
