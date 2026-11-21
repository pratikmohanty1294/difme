// Browser test of the exchange operator's live views against a real exchange-svc. Seeds orders through the API (a
// standing mandate wins an order that is bought, delivered and half returned; an intent no one can fill), then checks
// the Orders page (states, totals, filter, link to verified evidence) and the Partners page.
//   npx vite build --mode exchange --outDir /tmp/exchange
//   EXCHANGE_URL=http://127.0.0.1:8089 node e2e/operator-live.cjs /tmp/exchange /tmp/shots
const { chromium } = require('playwright'); const http = require('http'), fs = require('fs'), path = require('path');
const DIST = path.resolve(process.argv[2]), OUT = process.argv[3], REAL = process.env.EXCHANGE_URL;
if (!REAL) { console.error('EXCHANGE_URL is required'); process.exit(2); }
const api = async (method, p, body) => {
  const r = await fetch(`${REAL}/v1/exchange${p}`, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : (method === 'POST' ? '' : undefined) });
  const t = await r.text(); return { status: r.status, json: t ? JSON.parse(t) : {} };
};
const srv = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    let body = ''; req.on('data', (c) => body += c); req.on('end', async () => {
      try {
        const r = await fetch(REAL + req.url.replace(/^\/api/, ''), { method: req.method, headers: { 'Content-Type': 'application/json' }, body: body || undefined });
        res.writeHead(r.status, { 'Content-Type': 'application/json' }); res.end(await r.text());
      } catch { res.writeHead(502); res.end('{}'); }
    }); return;
  }
  let f = path.join(DIST, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(DIST) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html');
  const t = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
  res.writeHead(200, { 'Content-Type': t[path.extname(f)] ?? 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});

srv.listen(18187, async () => {
  const checks = []; const check = (k, ok) => { checks.push(ok); console.log((ok ? 'ok   ' : 'FAIL ') + k); };
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1360, height: 1000 } });
  const errors = []; p.on('pageerror', (e) => errors.push(e.message));
  try {
    const ref = `ops-${Date.now()}`;
    const m = await api('POST', '/fr01-buy/mandates', { partnerRef: ref, limitMinor: 2500000, maxPerOrderMinor: 2500000, minYieldPct: 4,
      depositMinor: 2000000, budgetTotalMinor: 2500000, merchantCount: 2, goodsCount: 5, validityDays: 5, cardJurisdiction: 'IN_DOMESTIC_AFA' });
    const o = await api('POST', '/fr01-buy/intents', { riskScore: 900, offerSanctioned: true, listMinor: 2000000, discountBps: 2000, feeMinor: 20000,
      maxPriceMinor: 1900000, order: { merchant: 'Electronics Store A' } });
    const aid = o.json.auctionId;
    for (let i = 0; i < 50 && !(await api('GET', `/auctions/${aid}`)).json.award; i++) await new Promise((r) => setTimeout(r, 100));
    await api('POST', `/auctions/${aid}/execute`, { merchantId: 'M1' });
    await api('POST', `/auctions/${aid}/sim/delivery`);
    const ret = await api('POST', `/auctions/${aid}/returns`, { fractionBps: 5000 });
    // A request no partner can fill: the fee leaves room for a yield of only ₹10, below every standing floor.
    const none = await api('POST', '/fr01-buy/intents', { riskScore: 900, offerSanctioned: true, listMinor: 100000, discountBps: 2000, feeMinor: 18999, maxPriceMinor: 99000 });
    await new Promise((r) => setTimeout(r, 500));
    check('seeded: mandate, order bought + delivered + half returned, unfilled request', m.status === 201 && ret.status === 200 && none.status === 202);

    await p.goto('http://127.0.0.1:18187/#/exchange'); await p.waitForTimeout(2500);
    let t = await p.locator('main').innerText();
    check('nav shows Orders and Partners, not the simulated pages', (await p.locator('aside').first().innerText()).includes('Orders') && !(await p.locator('aside').first().innerText()).includes('Order book'));
    check('order listed as partly returned with merchant and amounts', t.includes(String(aid)) && t.includes('Partly returned') && t.includes('Electronics Store A') && t.includes('₹16,840'));
    check('refund ₹8,420 shown (half of B)', t.includes('₹8,420'));
    check('unfilled request listed as no match', t.includes(String(none.json.auctionId)) && t.includes('No match'));
    // Totals as the exchange computes them (net of reversals and voided yield), shown on the page. On a fresh exchange
    // these are F ₹100 (200 − 100 reversed) and Y ₹320 (640 − 320 voided); compared with the API so other orders don't matter.
    const tot = (await api('GET', '/orders?limit=1')).json.totals;
    const rs = (m) => `₹${(m / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
    check(`totals match the exchange (fees ${rs(tot.feesMinor)}, yield ${rs(tot.partnerYieldMinor)}, refunded ${rs(tot.refundedMinor)})`,
      new RegExp(`platform fees \\(f\\)\\s+${rs(tot.feesMinor)}`, 'i').test(t) && new RegExp(`partner yield \\(y\\)\\s+${rs(tot.partnerYieldMinor)}`, 'i').test(t)
      && new RegExp(`refunded\\s+${rs(tot.refundedMinor)}`, 'i').test(t));
    await p.screenshot({ path: path.join(OUT, 'operator-orders.png'), fullPage: true });
    await p.getByRole('button', { name: /^No match/ }).click(); await p.waitForTimeout(800);
    t = await p.locator('main').innerText();
    check('filter: only no-match orders', t.includes(String(none.json.auctionId)) && !t.includes(String(aid)));
    await p.getByRole('button', { name: 'All' }).click(); await p.waitForTimeout(800);
    await p.getByRole('link', { name: String(aid) }).click(); await p.waitForTimeout(2500);
    t = await p.locator('main').innerText();
    check('auction link opens its evidence, which verifies', t.includes('Chain verifies') && t.includes('refund'));
    await p.goto('http://127.0.0.1:18187/#/exchange/network'); await p.waitForTimeout(2000);
    t = await p.locator('main').innerText();
    check('partners page lists the partner with its win and tier', t.includes(ref) && t.includes('cold start') && !t.includes('Aeron'));
    await p.screenshot({ path: path.join(OUT, 'operator-partners.png'), fullPage: true });
    // Auction config: the exchange's real settings. Raise the risk gate, see it refuse an intent, then reset.
    await p.goto('http://127.0.0.1:18187/#/exchange/config'); await p.waitForTimeout(1500);
    await p.getByLabel('Minimum buyer risk score').fill('950');
    await p.getByLabel('Changed by').fill('e2e-operator');
    await p.getByRole('button', { name: /^Save 1 change/ }).click(); await p.waitForTimeout(800);
    const st = (await api('GET', '/fr01-buy/settings')).json;
    check('config saved to the exchange (minRiskScore 950, logged with the name)', st.settings.minRiskScore === 950 && st.log.some((c) => c.by === 'e2e-operator' && c.field === 'minRiskScore'));
    const refused = await api('POST', '/fr01-buy/intents', { riskScore: 900, offerSanctioned: true, listMinor: 1000000, discountBps: 2000, feeMinor: 20000, maxPriceMinor: 950000 });
    check('a 900-risk intent is now refused (RISK_TOO_LOW)', refused.status === 422 && JSON.stringify(refused.json).includes('RISK_TOO_LOW'));
    t = await p.locator('main').innerText();
    check('change log row shown on the page', t.includes('e2e-operator') && t.includes('850 → 950'));
    await p.getByRole('button', { name: /Reset to defaults/ }).click();
    await p.getByRole('button', { name: /^Save 1 change/ }).click(); await p.waitForTimeout(800);
    check('reset to defaults (850)', (await api('GET', '/fr01-buy/settings')).json.settings.minRiskScore === 850);
    await p.screenshot({ path: path.join(OUT, 'operator-config.png'), fullPage: true });
    check('no page errors', errors.length === 0);
  } catch (e) {
    console.log('FAIL step:', e.message.split('\n')[0]); checks.push(false);
    await p.screenshot({ path: path.join(OUT, 'operator-failed.png'), fullPage: true });
  }
  await b.close(); srv.close(); process.exitCode = checks.every(Boolean) ? 0 : 1;
});
