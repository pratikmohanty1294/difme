import { useEffect, useState, type ReactNode } from 'react';
import { LIVE, exchange } from '../api/exchange';
import {
  Activity, BookOpen, Bot, Boxes, ClipboardList, ExternalLink, Gauge, Globe, Landmark, LayoutDashboard, Menu, Network, PlayCircle, RotateCcw, ScrollText,
  ShieldCheck, SlidersHorizontal, Sparkles, Wallet, X,
} from 'lucide-react';
import { href, type Route } from '../router';
import { APPS, CURRENT, IS_WEB, WEBSITE_URL, type RoleApp } from '../apps';
import { useEngine } from '../sim/engine';
import { Segmented, cx } from './ui';
import { TraderInfo } from './TraderInfo';
import type { Speed } from '../sim/types';

type Item = { route: Route; label: string; icon: typeof Bot };
type Group = { title: string; tone: string; app?: RoleApp; module?: string; items: Item[] };
// The website groups DiscountAI into its three apps; each app shows only its own group.
const GROUPS: Group[] = [
  { title: 'DiscountAI', tone: 'bg-brand-500', items: [{ route: 'overview', label: 'Product tour', icon: LayoutDashboard }, { route: 'demo', label: 'Guided demo', icon: PlayCircle }] },
  {
    title: 'ShopBot AI', app: 'shopbot', module: 'Buyer', tone: 'bg-buyer-600', items: [
      { route: 'buyer', label: 'Shopping agent', icon: Bot },
      { route: 'buyer/requests', label: 'My requests', icon: ClipboardList },
    ],
  },
  {
    title: 'Exchange OME', app: 'exchange', module: 'Exchange', tone: 'bg-exchange-600', items: [
      { route: 'exchange', label: 'Live auction', icon: Activity },
      { route: 'exchange/orderbook', label: 'Order book', icon: BookOpen },
      { route: 'exchange/config', label: 'Auction config', icon: SlidersHorizontal },
      { route: 'exchange/network', label: 'Network & nodes', icon: Network },
      { route: 'audit', label: 'Proof chain', icon: ShieldCheck },
      { route: 'audit/compliance', label: 'Regulator node', icon: Landmark },
    ],
  },
  {
    title: 'Buying Partner', app: 'investor', module: 'Partner', tone: 'bg-investor-600', items: [
      { route: 'investor', label: 'Command center', icon: Gauge },
      { route: 'investor/factory', label: 'Smart mandate factory', icon: Sparkles },
      { route: 'investor/mandates', label: 'Mandates', icon: Wallet },
    ],
  },
];
const VISIBLE = IS_WEB ? GROUPS : GROUPS.filter((g) => g.app === CURRENT!.id);
const APP_TONE: Record<RoleApp, string> = { shopbot: 'bg-buyer-600', exchange: 'bg-exchange-600', investor: 'bg-investor-600' };

export function Shell({ route, children }: { route: Route; children: ReactNode }) {
  const { state, setSpeed, reset } = useEngine();
  const [open, setOpen] = useState(false);
  const live = state.requests.filter((r) => r.status === 'Pending' || r.status === 'Matched').length;

  const nav = (
    <nav className="flex flex-col gap-5 px-3 py-4" aria-label="Main">
      {VISIBLE.map((g) => (
        <div key={g.title}>
          <div className="flex items-center gap-2 px-2 mb-1.5">
            <span className={cx('h-2 w-2 rounded-sm', g.tone)} />
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-400">{g.title}</span>
            {IS_WEB && g.app
              ? <a href={APPS[g.app].url} target="_blank" rel="noopener" className="ml-auto inline-flex items-center gap-1 text-[10.5px] text-ink-400 hover:text-white" title={`Open the ${g.title} app`}>App <ExternalLink size={11} /></a>
              : g.module && <span className="ml-auto font-mono text-[10px] text-ink-500">{g.module}</span>}
          </div>
          <ul className="flex flex-col gap-0.5">
            {g.items.map((it) => {
              const active = route === it.route;
              const Icon = it.icon;
              return (
                <li key={it.route}>
                  <a href={href(it.route)} onClick={() => setOpen(false)} aria-current={active ? 'page' : undefined}
                    className={cx('flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] transition-colors',
                      active ? 'bg-white/10 text-white' : 'text-ink-300 hover:bg-white/5 hover:text-white')}>
                    <Icon size={16} strokeWidth={1.8} />
                    {it.label}
                    {it.route === 'exchange' && live > 0 && <span className="ml-auto rounded-full bg-exchange-600 px-1.5 text-[10.5px] font-semibold text-white">{live}</span>}
                  </a>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
      {!IS_WEB && (
        <div>
          <div className="flex items-center gap-2 px-2 mb-1.5">
            <span className="h-2 w-2 rounded-sm bg-brand-500" />
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-400">DiscountAI apps</span>
          </div>
          <ul className="flex flex-col gap-0.5">
            {Object.values(APPS).filter((a) => a.id !== CURRENT!.id).map((a) => (
              <li key={a.id}>
                <a href={a.url} target="_blank" rel="noopener" className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] text-ink-300 hover:bg-white/5 hover:text-white">
                  <span className={cx('h-2.5 w-2.5 rounded-full', APP_TONE[a.id])} />{a.name}<ExternalLink size={12} className="ml-auto opacity-60" />
                </a>
              </li>
            ))}
            <li>
              <a href={WEBSITE_URL} target="_blank" rel="noopener" className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] text-ink-300 hover:bg-white/5 hover:text-white">
                <Globe size={15} strokeWidth={1.8} />difme.example<ExternalLink size={12} className="ml-auto opacity-60" />
              </a>
            </li>
          </ul>
        </div>
      )}
    </nav>
  );

  return (
    <div className="min-h-full lg:grid lg:grid-cols-[248px_minmax(0,1fr)]">
      <aside className="hidden lg:flex flex-col bg-ink-950 sticky top-0 h-screen overflow-y-auto">
        <Brand />
        {nav}
        <div className="mt-auto px-5 py-4 text-[11px] text-ink-500 border-t border-white/5">
          Interactive prototype<br />All data simulated · amounts in ₹
        </div>
      </aside>

      {open && (
        <div className="lg:hidden fixed inset-0 z-40">
          <div className="absolute inset-0 bg-ink-950/50" onClick={() => setOpen(false)} />
          <aside className="absolute left-0 top-0 bottom-0 w-[264px] bg-ink-950 overflow-y-auto">
            <div className="flex items-center justify-between pr-3"><Brand /><button className="text-ink-300 p-2" onClick={() => setOpen(false)} aria-label="Close menu"><X size={18} /></button></div>
            {nav}
          </aside>
        </div>
      )}

      <div className="min-w-0 flex flex-col">
        <header className="sticky top-0 z-30 bg-white/90 backdrop-blur border-b border-ink-200">
          <div className="flex items-center gap-3 px-4 md:px-8 h-14">
            <button className="lg:hidden btn-ghost px-2" onClick={() => setOpen(true)} aria-label="Open menu"><Menu size={18} /></button>
            <span className="hidden sm:inline-flex items-center gap-1.5 rounded-md bg-amber-50 px-2 py-1 text-[11.5px] font-medium text-amber-800 ring-1 ring-inset ring-amber-200">
              <Boxes size={13} /> Prototype · simulated data
            </span>
            {live > 0 && (
              <a href={IS_WEB || CURRENT?.id === 'exchange' ? href('exchange') : href('buyer/requests')} className="inline-flex items-center gap-1.5 text-[12.5px] text-exchange-600 font-medium">
                <span className="h-2 w-2 rounded-full bg-exchange-600 live-dot" /> {live} request{live > 1 ? 's' : ''} in flight
              </a>
            )}
            <div className="ml-auto flex items-center gap-2">
              {LIVE ? <ExchangeStatus /> : (<>
                <span className="hidden md:inline text-xs text-ink-500">Demo pace</span>
                <Segmented<Speed> size="sm" value={state.speed} onChange={setSpeed}
                  options={[{ value: 'presenter', label: 'Presenter' }, { value: 'normal', label: 'Fast' }, { value: 'instant', label: 'Instant' }]} />
                <button className="btn-ghost px-2.5 py-1.5 text-xs" onClick={reset} title="Reset demo data"><RotateCcw size={14} /> <span className="hidden md:inline">Reset</span></button>
              </>)}
            </div>
          </div>
        </header>
        <main className="flex-1 px-4 md:px-8 py-7 max-w-[1400px] w-full">{children}</main>
        <footer className="px-4 md:px-8 py-5 text-xs text-ink-500 border-t border-ink-200 flex flex-wrap gap-x-6 gap-y-1">
          <span className="inline-flex items-center gap-1.5"><img src="/brand/difme-symbol.svg" alt="" width={260} height={112} className="h-3.5 w-auto" /> DiscountAI by DIFME · where value finds its way</span>
          <span className="inline-flex items-center gap-1.5"><ScrollText size={13} /> Exchange for Agentic Commerce · interactive prototype</span>
          <span>Proofs use real SHA-256 and ECDSA P-256 in your browser; no data leaves this page.</span>
          <span className="basis-full"><TraderInfo /></span>
        </footer>
      </div>
    </div>
  );
}

function Brand() {
  return (
    <a href={IS_WEB ? href('overview') : href(CURRENT!.home)} className="flex flex-col gap-2 px-5 pt-5 pb-4 shrink-0" aria-label={`DIFME · ${CURRENT?.name ?? 'DiscountAI'} home`}>
      <img src="/brand/difme-logo-on-dark.svg" alt="DIFME: where value finds its way" width={782} height={210} className="w-[180px] h-auto" />
      <span className="leading-tight border-t border-white/10 pt-2">
        <span className="block text-white font-display font-semibold tracking-tight">{CURRENT?.name ?? 'DiscountAI'}</span>
        <span className="block text-[10.5px] text-ink-400 uppercase tracking-[0.12em] whitespace-nowrap">{CURRENT ? `DiscountAI · ${CURRENT.tagline}` : 'Agentic commerce'}</span>
      </span>
    </a>
  );
}

/** Live builds: whether the exchange answers (checked every 15 s). */
function ExchangeStatus() {
  const [up, setUp] = useState<boolean | null>(null);
  useEffect(() => {
    let alive = true;
    const check = () => exchange.ping().then((ok) => alive && setUp(ok));
    check();
    const t = setInterval(check, 15_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return (
    <span className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium',
      up ? 'bg-emerald-50 text-emerald-700' : up === false ? 'bg-red-50 text-red-700' : 'bg-ink-100 text-ink-600')}>
      <span className={cx('h-2 w-2 rounded-full', up ? 'bg-emerald-500' : up === false ? 'bg-red-500' : 'bg-ink-400')} />
      {up ? 'Exchange live' : up === false ? 'Exchange unreachable' : 'Connecting…'}
    </span>
  );
}
