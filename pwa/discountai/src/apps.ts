// One codebase, four builds. VITE_APP picks which one this bundle is:
//   web      → difme.example: the full DiscountAI site (overview, guided demo, all three apps' screens)
//   shopbot  → shopbotai.difme.example: buyer PWA
//   exchange → exchangeome.difme.example: order matching engine + settlement & audit PWA
//   buying partner → investorai.difme.example: buying partner PWA
import type { Route } from './router';

export type AppId = 'web' | 'shopbot' | 'exchange' | 'investor';
export type RoleApp = Exclude<AppId, 'web'>;

export interface AppInfo {
  id: RoleApp;
  name: string;
  tagline: string;
  url: string;
  home: Route;
  routes: Route[];
  /** Tailwind colour family used for this app's accents. */
  tone: 'buyer' | 'exchange' | 'investor';
  summary: string;
  features: string[];
}

export const WEBSITE_URL = 'https://difme.example';

export const APPS: Record<RoleApp, AppInfo> = {
  shopbot: {
    id: 'shopbot', name: 'ShopBot AI', tagline: 'For buyers', url: 'https://shopbotai.difme.example', home: 'buyer', tone: 'buyer',
    routes: ['buyer', 'buyer/requests'],
    summary: 'A shopping agent that compares stores and card offers. When the best deal needs a card you don\'t hold, a buying partner\'s agent on the exchange buys it in the buying partner\'s name and ships it to your address.',
    features: ['Live prices from Indian stores', 'Card and bank offer check', 'Request tracking and settlement'],
  },
  exchange: {
    id: 'exchange', name: 'Exchange OME', tagline: 'For the exchange operator', url: 'https://exchangeome.difme.example', home: 'exchange', tone: 'exchange',
    routes: ['exchange', 'exchange/orderbook', 'exchange/config', 'exchange/attributes', 'exchange/reports', 'exchange/network', 'audit', 'audit/compliance'],
    summary: 'The order matching engine. Buying partner agents bid in a 50 ms score-based batch auction, the best score wins, and every step is settled and logged in a signed proof chain.',
    features: ['Live auction and order book', 'Auction rules and network health', 'Proof chain and regulator view'],
  },
  investor: {
    id: 'investor', name: 'Buying Partner', tagline: 'For buying partners', url: 'https://investorai.difme.example', home: 'investor', tone: 'investor',
    routes: ['investor', 'investor/factory', 'investor/mandates'],
    summary: 'Turns an idle credit line, UPI account or token into a smart mandate with your rules. Your agent buys on behalf of matched buyers and earns yield on every purchase it completes.',
    features: ['Command center with yield', 'Smart mandate factory', 'Mandate rules and limits'],
  },
};

export const APP: AppId = ((import.meta.env.VITE_APP as string) || 'web') as AppId;
export const IS_WEB = APP === 'web';
export const CURRENT: AppInfo | null = IS_WEB ? null : APPS[APP as RoleApp];

/** The app that owns a route outside the website (overview and demo belong to the website). */
export function ownerOf(route: Route): RoleApp | 'web' {
  for (const a of Object.values(APPS)) if (a.routes.includes(route)) return a.id;
  return 'web';
}

/** True when this bundle can show the route itself. The website shows everything. */
export const owns = (route: Route) => IS_WEB || CURRENT!.routes.includes(route);

export const homeRoute: Route = CURRENT?.home ?? 'overview';
