import { Activity, ArrowRight, Bot, Check, ExternalLink, Gauge, ShieldCheck } from 'lucide-react';
import { ARCH_LAYERS, SCENARIO, SPLIT } from '../sim/data';
import { inr } from '../sim/format';
import { href } from '../router';
import { APPS, type AppInfo } from '../apps';
import { Card } from '../components/ui';

const APP_STYLE: Record<AppInfo['tone'], { icon: typeof Bot; chip: string; bar: string }> = {
  buyer: { icon: Bot, chip: 'bg-buyer-50 text-buyer-600', bar: 'bg-buyer-600' },
  exchange: { icon: Activity, chip: 'bg-exchange-50 text-exchange-600', bar: 'bg-exchange-600' },
  investor: { icon: Gauge, chip: 'bg-investor-50 text-investor-600', bar: 'bg-investor-600' },
};

/** The three DiscountAI apps, each also installable on its own subdomain. */
function AppSections() {
  return (
    <section aria-labelledby="apps-h">
      <div className="flex flex-wrap items-baseline justify-between gap-2 mb-3">
        <h2 id="apps-h" className="text-lg font-semibold">DiscountAI apps</h2>
        <p className="text-[13px] text-ink-500">Each one is an installable app on its own address. Open it, then use your browser's “Install app” or “Add to Home screen”.</p>
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        {[APPS.shopbot, APPS.exchange, APPS.investor].map((a) => {
          const st = APP_STYLE[a.tone];
          return (
            <article key={a.id} className="card overflow-hidden flex flex-col">
              <div className={`h-1.5 ${st.bar}`} />
              <div className="p-5 flex flex-col gap-3 flex-1">
                <div className="flex items-center gap-3">
                  <img src={`/icons/${a.id}-192.png`} alt="" width={44} height={44} className="h-11 w-11 rounded-xl ring-1 ring-ink-200" />
                  <div className="leading-tight">
                    <h3 className="font-semibold text-ink-950 text-[17px]">{a.name}</h3>
                    <p className="text-xs text-ink-500">{a.tagline} · <span className="font-mono">{a.url.replace('https://', '')}</span></p>
                  </div>
                </div>
                <p className="text-[13.5px] text-ink-600">{a.summary}</p>
                <ul className="flex flex-col gap-1 text-[13px] text-ink-700">
                  {a.features.map((f) => <li key={f} className="flex items-start gap-2"><Check size={14} className="mt-0.5 text-ink-400 shrink-0" />{f}</li>)}
                </ul>
                <div className="mt-auto pt-2 flex flex-wrap gap-2">
                  <a href={a.url} target="_blank" rel="noopener" className="btn-primary">Open {a.name} <ExternalLink size={14} /></a>
                  <a href={href(a.home)} className="btn-secondary">Try it here</a>
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

const s = SPLIT;

export default function Overview() {
  return (
    <div className="flex flex-col gap-6">
      <section className="card overflow-hidden">
        <div className="grid lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
          <div className="p-7 md:p-9">
            <p className="label mb-3">DiscountAI · Exchange for Agentic Commerce</p>
            <h1 className="text-[30px] md:text-[36px] leading-[1.12] font-semibold tracking-tight text-ink-950 max-w-xl">
              A buyer saves 12% without the right card: a buying partner's agent buys it for them in under 50 ms, and an idle credit line earns yield.
            </h1>
            <p className="mt-4 text-ink-600 max-w-xl">
              DiscountAI is three apps working together: ShopBot AI for buyers, Exchange OME that matches them, and Buying Partner for the buying partners whose agents complete the purchase. No card is ever lent: the buying partner is the purchaser of record and the merchant ships to the buyer. On this site all three apps run on one shared simulation, with escrow settlement, two-leg refunds and a cryptographic proof chain.
            </p>
            <div className="mt-6 flex flex-wrap gap-2">
              <a href={href('demo')} className="btn-primary">Run the end-to-end guided demo <ArrowRight size={15} /></a>
              <a href={href('buyer')} className="btn-secondary">Explore freely</a>
            </div>
          </div>
          <div className="bg-ink-950 text-ink-200 p-7 md:p-9">
            <p className="text-[11px] uppercase tracking-[0.1em] text-ink-400 mb-4">Worked example</p>
            <dl className="grid grid-cols-[1fr_auto] gap-y-2.5 text-sm">
              <dt>{SCENARIO.product}, list price P</dt><dd className="tabular text-right">{inr(s.list)}</dd>
              <dt>Bank X instant discount, 20% (buyer has no Bank X card)</dt><dd className="tabular text-right">−{inr(s.discount)}</dd>
              <dt>Merchant charges the buying partner's Bank X card (A)</dt><dd className="tabular text-right">{inr(s.A)}</dd>
              <dt>Buying partner yield (Y) + platform fee (F)</dt><dd className="tabular text-right">+{inr(s.Y + s.F)}</dd>
              <dt className="text-ink-400 pl-3">Buying partner payout A + Y, before card due date</dt><dd className="tabular text-right text-ink-400">{inr(s.payout)}</dd>
              <dt className="text-ink-400 pl-3">Platform fee F ({inr(s.guaranteeFund)} to the Guarantee Fund)</dt><dd className="tabular text-right text-ink-400">{inr(s.F)}</dd>
              <dt className="pt-2.5 border-t border-white/10 font-medium text-white">Buyer pays B, captured on shipment</dt><dd className="pt-2.5 border-t border-white/10 tabular text-right font-semibold text-white">{inr(s.B)}</dd>
              <dt className="text-[#7fd1cf]">Buyer saves</dt><dd className="tabular text-right text-[#7fd1cf]">{inr(s.buyerSaves)}</dd>
            </dl>
          </div>
        </div>
      </section>

      <AppSections />

      <section>
        <h2 className="text-lg font-semibold mb-3">Guided scenario</h2>
        <ol className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {[
            { n: 1, icon: Bot, tone: 'text-buyer-600 bg-buyer-50', title: 'Ask the buyer agent', text: 'MCP discovery finds the Bank X offer and a gap. Choose “A buying partner buys it and ships it to me”.', to: href('buyer') },
            { n: 2, icon: Activity, tone: 'text-exchange-600 bg-exchange-50', title: 'Watch the 50 ms race', text: 'Buying partner agents bid a yield; SBBA picks a provisional winner and two backups. Declines fail over silently.', to: href('exchange') },
            { n: 3, icon: Gauge, tone: 'text-investor-600 bg-investor-50', title: 'See the buying partner side', text: 'The winning agent buys at the merchant with its own card; A + Y = ₹8,600 is paid out after shipment.', to: href('investor') },
            { n: 4, icon: ShieldCheck, tone: 'text-audit-600 bg-audit-50', title: 'Verify the proofs', text: 'POI → POC, signed and hash-linked. Try the tamper test to see the chain break.', to: href('audit') },
          ].map((st) => (
            <li key={st.n}>
              <a href={st.to} className="card h-full flex flex-col p-5 hover:border-ink-300 transition-colors">
                <span className={`grid h-9 w-9 place-items-center rounded-lg ${st.tone}`}><st.icon size={18} /></span>
                <p className="mt-3 text-xs text-ink-500">Step {st.n}</p>
                <p className="font-semibold text-ink-900">{st.title}</p>
                <p className="mt-1 text-[13.5px] text-ink-600 flex-1">{st.text}</p>
                <span className="mt-3 text-sm font-medium text-brand-700 inline-flex items-center gap-1">Open <ArrowRight size={14} /></span>
              </a>
            </li>
          ))}
        </ol>
      </section>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
        <Card title="Seven-layer architecture" sub="Agent PA/PG for the buyer leg, Merchant PSP for the purchase leg">
          <ol className="flex flex-col gap-1.5">
            {ARCH_LAYERS.map((l) => (
              <li key={l.id} className="grid grid-cols-[44px_minmax(0,180px)_minmax(0,1fr)] items-baseline gap-3 rounded-lg px-3 py-2 odd:bg-ink-50">
                <span className="font-mono text-xs text-brand-700 font-medium">{l.id}</span>
                <span className="font-medium text-ink-900">{l.name}</span>
                <span className="text-[13px] text-ink-600">{l.text}</span>
              </li>
            ))}
          </ol>
        </Card>
        <Card title="How DiscountAI keeps every order safe" sub="The design principles behind each purchase">
          <ul className="flex flex-col gap-3 text-[13.5px] text-ink-700">
            <li><b className="text-ink-900">No credential lending.</b> The buying partner's own agent checks out at the merchant with a single-use network token; nothing reaches the buyer.</li>
            <li><b className="text-ink-900">Buyer hold, not a retail pre-auth.</b> The buyer's card is held for B only after a match and captured when the item ships. Buying partners get no pre-auth holds; a 5% security deposit and a ₹0 card check cover solvency.</li>
            <li><b className="text-ink-900">Two-phase commit and failover.</b> The buyer hears “confirmed” only after the merchant approves. A declining buying partner is slashed and the next bidder takes over; if all fail, the Guarantee Fund backstops the order.</li>
            <li><b className="text-ink-900">Refunds in two legs.</b> The merchant refunds the buying partner's card while DiscountAI refunds the buyer at once from escrow, with clawback from the buying partner after payout.</li>
          </ul>
        </Card>
      </div>
    </div>
  );
}
