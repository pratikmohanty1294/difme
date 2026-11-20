import { lazy, Suspense, type ComponentType } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { EngineProvider } from './sim/engine';
import { Shell } from './components/Shell';
import { useRoute } from './router';

// Free-tier friendly: every screen is its own lazily loaded chunk, and screens an app doesn't own are
// compiled out of that app's bundle (VITE_APP is a build-time constant, so the dead branches disappear).
const APP = import.meta.env.VITE_APP;
const WEB = APP === 'web';
const SHOP = WEB || APP === 'shopbot';
const OME = WEB || APP === 'exchange';
const INV = WEB || APP === 'investor';

type Page = ComponentType<{ focus?: string }>;
const none = null as unknown as Page;
const Overview: Page = WEB ? lazy(() => import('./pages/Overview')) : none;
const GuidedDemo: Page = WEB ? lazy(() => import('./pages/GuidedDemo')) : none;
const BuyerAgent: Page = SHOP ? lazy(() => import('./pages/buyer/BuyerAgent')) : none;
const MyRequests: Page = SHOP ? lazy(() => import('./pages/buyer/MyRequests')) : none;
const CommandCenter: Page = INV ? lazy(() => import('./pages/investor/CommandCenter')) : none;
const MandateFactory: Page = INV ? lazy(() => import('./pages/investor/MandateFactory')) : none;
const Mandates: Page = INV ? lazy(() => import('./pages/investor/Mandates')) : none;
const LiveAuction: Page = OME ? lazy(() => import('./pages/exchange/LiveAuction')) : none;
const OrderBook: Page = OME ? lazy(() => import('./pages/exchange/OrderBook')) : none;
const AuctionConfig: Page = OME ? lazy(() => import('./pages/exchange/AuctionConfig')) : none;
const Network: Page = OME ? lazy(() => import('./pages/exchange/Network')) : none;
const ProofChain: Page = OME ? lazy(() => import('./pages/audit/ProofChain')) : none;
const Compliance: Page = OME ? lazy(() => import('./pages/audit/Compliance')) : none;

function App() {
  const { route, param } = useRoute();
  const page = (() => {
    switch (route) {
      case 'demo': return <GuidedDemo />;
      case 'buyer': return <BuyerAgent />;
      case 'buyer/requests': return <MyRequests key={param} focus={param} />;
      case 'investor': return <CommandCenter />;
      case 'investor/factory': return <MandateFactory />;
      case 'investor/mandates': return <Mandates key={param} focus={param} />;
      case 'exchange': return <LiveAuction key={param} focus={param} />;
      case 'exchange/orderbook': return <OrderBook />;
      case 'exchange/config': return <AuctionConfig />;
      case 'exchange/network': return <Network />;
      case 'audit': return <ProofChain key={param} focus={param} />;
      case 'audit/compliance': return <Compliance />;
      default: return <Overview />;
    }
  })();
  return (
    <Shell route={route}>
      <Suspense fallback={<p className="text-sm text-ink-500 live-dot">Loading…</p>}>{page}</Suspense>
    </Shell>
  );
}

createRoot(document.getElementById('root')!).render(
  <EngineProvider>
    <App />
  </EngineProvider>,
);
