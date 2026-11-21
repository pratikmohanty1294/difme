// Browser test of the investor PWA against a real exchange-svc: create a mandate in the factory, then pause, resume
// and terminate it, checking each change in the exchange's own records (not just on screen).
//   npx vite build --mode investor --outDir /tmp/investor
//   EXCHANGE_URL=http://127.0.0.1:8089 node e2e/investor-live.cjs /tmp/investor /tmp/shots
const { chromium } = require('playwright'); const http = require('http'), fs = require('fs'), path = require('path');
const DIST = path.resolve(process.argv[2]), OUT = process.argv[3], REAL = process.env.EXCHANGE_URL;
if (!REAL) { console.error('EXCHANGE_URL is required'); process.exit(2); }
const calls = [];
const srv = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    let body = ''; req.on('data', (c) => body += c); req.on('end', async () => {
      calls.push(`${req.method} ${req.url.split('?')[0]}`);
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
const status = async (id) => (await (await fetch(`${REAL}/v1/exchange/fr01-buy/mandates/${id}`)).json()).status;
srv.listen(18183, async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 1000 } });
  const errors = []; p.on('pageerror', (e) => errors.push(e.message));
  const checks = []; const check = (k, ok) => { checks.push([k, ok]); console.log((ok ? 'ok   ' : 'FAIL ') + k); };
  try {
    await p.goto('http://127.0.0.1:18183/#/investor/factory'); await p.waitForTimeout(800);
    check('no demo mandates in a live build', !(await p.innerText('body')).includes('M-2026-8822'));
    await p.getByRole('button', { name: /Interpret instruction/ }).click(); await p.waitForTimeout(800);
    await p.getByRole('button', { name: /Continue/ }).first().click(); await p.waitForTimeout(300);
    await p.getByRole('button', { name: /Card \/ fiat credit/ }).click();
    await p.getByRole('button', { name: 'Continue to Bank X' }).click();
    await p.locator('#otp').fill('123456'); await p.getByRole('button', { name: 'Verify' }).click(); await p.waitForTimeout(1300);
    await p.getByRole('button', { name: /^Continue/ }).last().click(); await p.waitForTimeout(300);
    await p.getByRole('button', { name: /^Review/ }).click(); await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(OUT, 'investor-review.png'), fullPage: true });
    await p.getByRole('button', { name: /Validate, sign and deploy/ }).click();
    await p.waitForTimeout(8000);
    await p.screenshot({ path: path.join(OUT, 'investor-deployed.png'), fullPage: true });
    const reg = calls.find((c) => c === 'POST /api/v1/exchange/fr01-buy/mandates');
    check('mandate registered on the exchange', !!reg);
    await p.goto('http://127.0.0.1:18183/#/investor/mandates'); await p.waitForTimeout(1200);
    // Find the exchange id of what the app registered (it reads its mandates back with GET .../mandates/{id}).
    const resp = await new Promise((resolve) => { p.on('response', (r) => { if (/mandates\/\d+$/.test(r.url())) resolve(r.url()); }); setTimeout(() => resolve(null), 12000); });
    const id = resp && resp.match(/mandates\/(\d+)$/)[1];
    check('app reads the mandate back from the exchange', !!id);
    if (id) {
      check('exchange: ACTIVE after deploy', (await status(id)) === 'ACTIVE');
      // The factory's default custom rules (dmn-compile DEFAULT_CUSTOM) allow only listed merchants: the exchange
      // enforces them, so the mandate bids for an approved merchant and not for an unknown one. Needs a fresh exchange
      // (no other mandates) and an hour outside the rules' 00-06 IST night ban.
      const intent = async (merchant) => (await (await fetch(`${REAL}/v1/exchange/fr01-buy/intents`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ riskScore: 900, offerSanctioned: true, listMinor: 1000000,
          discountBps: 2000, feeMinor: 20000, maxPriceMinor: 950000, order: { merchant } }) })).json()).standingBids;
      const istHour = (new Date().getUTCHours() + 5 + (new Date().getUTCMinutes() >= 30 ? 1 : 0)) % 24;
      if (istHour >= 6) {
        check('custom rules: bids for an approved merchant', (await intent('Electronics Store A')) === 1);
        check('custom rules: no bid for an unknown merchant', (await intent('Unknown Shop')) === 0);
      } else console.log('skip  custom-rule bids (00-06 IST night ban in the default rules)');
      await p.getByRole('button', { name: /Pause bidding/ }).first().click(); await p.waitForTimeout(800);
      check('exchange: PAUSED after "Pause bidding"', (await status(id)) === 'PAUSED');
      await p.getByRole('button', { name: /Resume/ }).first().click(); await p.waitForTimeout(800);
      check('exchange: ACTIVE after "Resume"', (await status(id)) === 'ACTIVE');
      await p.getByRole('button', { name: /Terminate the contract/ }).first().click(); await p.waitForTimeout(300);
      await p.getByRole('button', { name: /Terminate|Confirm/ }).last().click(); await p.waitForTimeout(800);
      check('exchange: REVOKED after "Terminate"', (await status(id)) === 'REVOKED');
    }
    check('no page errors', errors.length === 0);
  } catch (e) {
    console.log('FAIL step:', e.message.split('\n')[0]); checks.push(['step', false]);
    await p.screenshot({ path: path.join(OUT, 'investor-failed.png'), fullPage: true });
  }
  console.log('exchange calls:', [...new Set(calls)].join(' | '));
  await b.close(); srv.close(); process.exitCode = checks.every(([, ok]) => ok) ? 0 : 1;
});
