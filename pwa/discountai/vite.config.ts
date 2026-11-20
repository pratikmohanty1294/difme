import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// One codebase, four builds (src/apps.ts), as in the prototype (ADR-020: buyer and investor apps are PWAs):
//   npm run build            → dist/           all screens (demo / guided tour)
//   npm run build:shopbot    → dist-shopbot/   ShopBot AI (buyer)        PWA
//   npm run build:investor   → dist-investor/  Buying Partner (investor) PWA
//   npm run build:exchange   → dist-exchange/  Exchange OME             PWA (also mirrored in the back-office)
// Everything is served same-origin behind the platform gateway; /api/* goes to the exchange.
type RoleApp = 'shopbot' | 'exchange' | 'investor';
const PWA: Record<RoleApp, { name: string; short: string; desc: string; start: string; theme: string }> = {
  shopbot: { name: 'ShopBot AI · DiscountAI', short: 'ShopBot AI', desc: 'Shopping agent that finds the best price and card offer and buys through the exchange.', start: '/#/buyer', theme: '#b4520c' },
  exchange: { name: 'Exchange OME · DiscountAI', short: 'Exchange OME', desc: 'Order matching engine: live auction, order book, network health and the settlement proof chain.', start: '/#/exchange', theme: '#0f7a55' },
  investor: { name: 'Buying Partner · DiscountAI', short: 'Buying Partner', desc: 'Turn an idle credit line or token into a smart mandate that earns commerce yield.', start: '/#/investor', theme: '#2453c2' },
};

function appHtml(app: RoleApp | 'web'): Plugin {
  return {
    name: 'app-html',
    transformIndexHtml(html) {
      if (app === 'web') return html;
      const p = PWA[app];
      return html
        .replace(/<title>[^<]*<\/title>/, `<title>${p.name}</title>`)
        .replace(/<meta name="description"[^>]*>/, `<meta name="description" content="${p.desc}" />`)
        .replace(/<link rel="icon"[^>]*>/, `<link rel="icon" type="image/png" href="/icons/${app}-192.png" />`)
        .replace(/<link rel="apple-touch-icon"[^>]*>/, `<link rel="apple-touch-icon" href="/icons/${app}-apple-180.png" />\n    <meta name="theme-color" content="${p.theme}" />`);
    },
  };
}

export default defineConfig(({ mode }) => {
  const app = (['shopbot', 'exchange', 'investor'].includes(mode) ? mode : 'web') as RoleApp | 'web';
  const plugins = [react(), appHtml(app)];

  if (app !== 'web') {
    const p = PWA[app];
    plugins.push(VitePWA({
      registerType: 'autoUpdate',
      injectRegister: 'script-defer',
      includeAssets: [`icons/${app}-*.png`],
      manifest: {
        id: '/',
        name: p.name,
        short_name: p.short,
        description: p.desc,
        start_url: p.start,
        scope: '/',
        display: 'standalone',
        orientation: 'any',
        background_color: '#f6f8fb',
        theme_color: p.theme,
        categories: ['finance', 'shopping', 'business'],
        icons: [
          { src: `/icons/${app}-192.png`, sizes: '192x192', type: 'image/png' },
          { src: `/icons/${app}-512.png`, sizes: '512x512', type: 'image/png' },
          { src: `/icons/${app}-maskable-512.png`, sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,png}'],
        globIgnores: ['icons/!(' + app + ')-*.png'], // ship only this app's icons in the offline cache
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
        runtimeCaching: [{ urlPattern: /\/api\//, handler: 'NetworkOnly' }], // exchange state is never served stale
      },
    }));
  }

  return {
    plugins,
    define: { 'import.meta.env.VITE_APP': JSON.stringify(app) },
    build: { outDir: app === 'web' ? 'dist' : `dist-${app}` },
    server: { port: 5180, strictPort: true, proxy: { '/api': process.env.DIFME_API ?? 'http://localhost:8089' } },
  };
});
