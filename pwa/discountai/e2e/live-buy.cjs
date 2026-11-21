// Browser test of the shopbot PWA's live buy: serves a built `--mode shopbot` bundle and answers /api/v1/exchange with
// the responses recorded from cluster run 44819780793 (no cluster or JVM needed).
//   npx vite build --mode shopbot --outDir /tmp/shopbot && node e2e/live-buy.cjs /tmp/shopbot /tmp/shots [refuse]
// Needs the playwright package and a Chromium (preinstalled on the dev containers). Exit 1 on any failed check.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const DIST = path.resolve(process.argv[2]), OUT = process.argv[3], REFUSE = process.argv[4] === 'refuse';
// EXCHANGE_URL=http://127.0.0.1:8089: forward /api/ to a real exchange-svc instead of the recorded answers (a standing
// mandate is registered first so the auction has a bid; it bids at its 4 % floor, the same split the checks expect).
const REAL = process.env.EXCHANGE_URL;
const calls = [];
// Exchange arithmetic for one standing bid at a 4 % yield floor (as the cluster answered): A = P − d·P, B = A + Y + F.
let split = null;
const fromIntent = (i) => { const a = i.listMinor - Math.round(i.listMinor * i.discountBps / 10000); const y = Math.round(a * 0.04);
  return { a, yield: y, fee: i.feeMinor, b: a + y + i.feeMinor, buyerSaves: i.listMinor - (a + y + i.feeMinor) }; };
const api = {
  'POST /api/v1/exchange/fr01-buy/intents': REFUSE
    ? [422, { findings: [{ code: 'RISK_UNSCORED', severity: 'BLOCK', message: 'risk check unavailable' }] }] : [202, { auctionId: 1795210509895001, maxYieldMinor: 379999, findings: [], standingBids: 1, riskSource: 'edge' }],
  'GET /api/v1/exchange/auctions/1795210509895001': [200, { auctionId: 1795210509895001, pack: 'fr01-buy', ranked: 1,
    top: [{ nodeRef: 1000001, askMinor: 64000, score: 0.93 }],
    award: { winner: { nodeRef: 1000001, askMinor: 64000, score: 0.93 }, split: { a: 1600000, b: 1684000, yield: 64000, fee: 20000, buyerSaves: 316000 }, collateralMinor: 320000, tier: 'COLD_START' } }],
  'POST /api/v1/exchange/auctions/1795210509895001/execute': [200, { auctionId: 1795210509895001, fulfilled: true, boughtBy: 1000001, slashedMinor: 0,
    attempts: [{ partnerId: 1000001, responseCode: '00' }], escrow: { holdId: 'auction-1795210509895001', heldMinor: 1684000, releasedMinor: 1600000, state: 'PARTLY_RELEASED' } }],
  'GET /api/v1/exchange/auctions/1795210509895001/evidence': [200, { chain: 'c1', sealed: true, artifacts: [], verdict: { hashes: true, links: true, seal: true, witnesses: true, notes: [] } }],
  'POST /api/v1/exchange/auctions/1795210509895001/sim/delivery': [200, { auctionId: 1795210509895001, verified: true, problems: [],
    escrow: { holdId: 'auction-1795210509895001', heldMinor: 1684000, releasedMinor: 1620000, state: 'PARTLY_RELEASED' }, yieldReleaseAtMs: Date.now() + 7 * 86400000 }],
  'GET /api/v1/exchange/keys': [200, { 'exchange-svc': 'k', 'notary-1': 'k' }],
};
const srv = http.createServer((req, res) => {
  const key = `${req.method} ${req.url.split('?')[0]}`;
  if (req.url.startsWith('/api/') && REAL) {
    let body = ''; req.on('data', (c) => body += c); req.on('end', async () => {
      calls.push(key + (body ? ' ' + body.slice(0, 160) : ''));
      if (key.endsWith('/intents') && body) split = fromIntent(JSON.parse(body));
      const r = await fetch(REAL + req.url.replace(/^\/api/, ''), { method: req.method, headers: { 'Content-Type': 'application/json' }, body: body || undefined });
      res.writeHead(r.status, { 'Content-Type': 'application/json' }); res.end(await r.text());
    }); return;
  }
  if (req.url.startsWith('/api/')) {
    let body = ''; req.on('data', (c) => body += c); req.on('end', () => {
      calls.push(key + (body ? ' ' + body.slice(0, 160) : ''));
      if (key.endsWith('/intents') && body) split = fromIntent(JSON.parse(body));
      let [code, json] = api[key] ?? [404, { error: 'not faked: ' + key }];
      if (split && json.award) json = { ...json, award: { ...json.award, split } };
      if (split && json.escrow) json = { ...json, escrow: { ...json.escrow, heldMinor: split.b,
        releasedMinor: key.endsWith('/delivery') ? split.a + split.fee : split.a } };
      res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(json));
    }); return;
  }
  let f = path.join(DIST, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(DIST) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html');
  const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.png': 'image/png' };
  res.writeHead(200, { 'Content-Type': types[path.extname(f)] ?? 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});
srv.listen(18181, async () => {
  if (REAL && !REFUSE) {
    const m = await fetch(REAL + '/v1/exchange/fr01-buy/mandates', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ partnerRef: 'e2e-' + Date.now(), limitMinor: 2500000, maxPerOrderMinor: 2500000, minYieldPct: 4, depositMinor: 2000000,
        budgetTotalMinor: 2500000, merchantCount: 2, goodsCount: 5, validityDays: 5, cardJurisdiction: 'IN_DOMESTIC_AFA' }) });
    console.log('standing mandate:', m.status, (await m.text()).slice(0, 120));
  }
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = []; p.on('pageerror', (e) => { errors.push(e.message); console.log('PAGEERROR', e.message); });
  await p.goto('http://127.0.0.1:18181/');
  await p.waitForTimeout(1500);
  await p.getByText('Try:').click();
  await p.waitForTimeout(6000);
    await p.getByRole('button', { name: 'Find a buying partner' }).click();
  await p.waitForTimeout(1000);
  await p.getByRole('button', { name: /Use my saved card/ }).click();
  await p.waitForTimeout(1500);
  await p.getByLabel(/Let DiscountAI arrange it/).check();
  await p.getByLabel(/Hold the quoted amount/).check();
  await p.getByRole('button', { name: 'Save my choice' }).click();
  await p.waitForTimeout(300);
  await p.getByRole('button', { name: /Send to the exchange/ }).click();
  await p.waitForTimeout(5000);
  await p.screenshot({ path: path.join(OUT, REFUSE ? 'refused.png' : 'bought.png'), fullPage: true });
  const txt = await p.locator('main').innerText();
  const page = await p.innerText('body');
  const inr = (m) => '₹' + Math.round(m / 100).toLocaleString('en-IN');
  const want = REFUSE ? ['Refused by the exchange: RISK_UNSCORED']
    : ['Intent accepted', 'Awarded to node', 'Hold of', 'Bought by node', 'Evidence chain verified', 'Delivered',
       inr(split.b), `Saved ${inr(split.buyerSaves)}`];
  const checks = [...want.map((k) => [k, txt.includes(k)]),
    ['no browser rule pre-check', !calls.some((c) => c.startsWith('POST /api/rules'))],
    ['no prototype labels', !page.includes('All data simulated') && !page.includes('Prototype control')],
    ['no page errors', errors.length === 0]];
  for (const [k, ok] of checks) console.log(ok ? 'ok  ' : 'FAIL', k);
  process.exitCode = checks.every(([, ok]) => ok) ? 0 : 1;
  await b.close(); srv.close();
  console.log('exchange calls:', calls.map((c) => c.split(' {')[0]).join(' | '));
});
