import { useEffect, useState } from 'react';
import { APPS, WEBSITE_URL, homeRoute, ownerOf, owns } from './apps';

export type Route =
  | 'overview' | 'demo'
  | 'buyer' | 'buyer/requests'
  | 'investor' | 'investor/factory' | 'investor/mandates'
  | 'exchange' | 'exchange/orderbook' | 'exchange/config' | 'exchange/attributes' | 'exchange/reports' | 'exchange/network'
  | 'audit' | 'audit/compliance';

const ROUTES: Route[] = ['overview', 'demo','buyer', 'buyer/requests', 'investor', 'investor/factory', 'investor/mandates', 'exchange', 'exchange/orderbook', 'exchange/config', 'exchange/attributes', 'exchange/reports', 'exchange/network', 'audit', 'audit/compliance'];

function parse(): { route: Route; param?: string } {
  const raw = window.location.hash.replace(/^#\/?/, '');
  const [path, param] = raw.split('?id=');
  // In a role app, any route it doesn't own falls back to that app's home screen.
  const route = (ROUTES as string[]).includes(path) && owns(path as Route) ? (path as Route) : homeRoute;
  return { route, param };
}

export function useRoute() {
  const [r, setR] = useState(parse);
  useEffect(() => {
    const on = () => { setR(parse()); window.scrollTo({ top: 0 }); };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return r;
}

const path = (route: Route, id?: string) => `#/${route}${id ? `?id=${id}` : ''}`;

/** Link to a screen: in-app when this bundle has it, otherwise to the app (or website) that does. */
export const href = (route: Route, id?: string) => {
  if (owns(route)) return path(route, id);
  const owner = ownerOf(route);
  return `${owner === 'web' ? WEBSITE_URL : APPS[owner].url}/${path(route)}`; // the other app has its own data, so no id
};

export const isExternal = (route: Route) => !owns(route);

export const go = (route: Route, id?: string) => {
  if (owns(route)) window.location.hash = path(route, id);
  else window.location.href = href(route, id);
};
