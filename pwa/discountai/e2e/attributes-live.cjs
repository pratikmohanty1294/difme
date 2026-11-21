// Browser test of the rule-attribute master list against a real exchange-svc:
//  1. Exchange OME app: a bad CSV shows its problems and can't be saved; an Excel (.xlsx) master list uploads and saves.
//  2. The exchange now accepts and type-checks those attributes on intents.
//  3. Investor app: the custom rules editor lists the new attributes for expressions.
//   npx vite build --mode exchange --outDir /tmp/exchange && npx vite build --mode investor --outDir /tmp/investor
//   EXCHANGE_URL=http://127.0.0.1:8089 node e2e/attributes-live.cjs /tmp/exchange /tmp/investor <good.xlsx> <bad.csv> /tmp/shots
const { chromium } = require('playwright'); const http = require('http'), fs = require('fs'), path = require('path');
const [EX, INV, XLSX, BAD, OUT] = process.argv.slice(2).map((p) => path.resolve(p));
const REAL = process.env.EXCHANGE_URL;
if (!REAL) { console.error('EXCHANGE_URL is required'); process.exit(2); }

function serve(dist, port) {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      if (req.url.startsWith('/api/')) {
        let body = ''; req.on('data', (c) => body += c); req.on('end', async () => {
          try {
            const r = await fetch(REAL + req.url.replace(/^\/api/, ''), { method: req.method, headers: { 'Content-Type': 'application/json' }, body: body || undefined });
            res.writeHead(r.status, { 'Content-Type': 'application/json' }); res.end(await r.text());
          } catch { res.writeHead(502); res.end('{}'); }
        }); return;
      }
      let f = path.join(dist, decodeURIComponent(req.url.split('?')[0]));
      if (!f.startsWith(dist) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(dist, 'index.html');
      const t = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
      res.writeHead(200, { 'Content-Type': t[path.extname(f)] ?? 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
    });
    srv.listen(port, () => resolve(srv));
  });
}

(async () => {
  const s1 = await serve(EX, 18184), s2 = await serve(INV, 18185);
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 1000 } });
  const errors = []; p.on('pageerror', (e) => errors.push(e.message));
  const checks = []; const check = (k, ok) => { checks.push(ok); console.log((ok ? 'ok   ' : 'FAIL ') + k); };
  try {
    await p.goto('http://127.0.0.1:18184/#/exchange/attributes'); await p.waitForTimeout(1500);
    check('page shows the current catalog (defaults)', (await p.locator('main').innerText()).includes('merchant'));
    await p.setInputFiles('input[type=file]', BAD); await p.waitForTimeout(500);
    let t = await p.locator('main').innerText();
    check('bad CSV: unknown type reported', t.includes('type “date” isn’t number, string or boolean'));
    check('bad CSV: bad name reported', t.includes('“Amount” must start with a lower-case letter'));
    check('bad CSV: save disabled', await p.getByRole('button', { name: /Save to the exchange/ }).isDisabled());
    await p.setInputFiles('input[type=file]', XLSX); await p.waitForTimeout(800);
    t = await p.locator('main').innerText();
    check('xlsx: 9 attributes read (header synonyms, integer/text/yes-no types)', t.includes('attributes.xlsx · 9 attributes'));
    await p.getByRole('button', { name: /Save to the exchange/ }).click(); await p.waitForTimeout(1200);
    t = await p.locator('main').innerText();
    check('saved', t.includes('Saved: 9 attributes'));
    await p.screenshot({ path: path.join(OUT, 'attributes-saved.png'), fullPage: true });
    const cat = await (await fetch(`${REAL}/v1/exchange/fr01-buy/attributes`)).json();
    const w = cat.attributes.find((a) => a.key === 'warrantyYears'), g = cat.attributes.find((a) => a.key === 'giftWrap');
    check('exchange stores warrantyYears as number, giftWrap as boolean', w?.type === 'number' && g?.type === 'boolean' && w?.label === 'Warranty (years)');
    const intent = (attributes) => fetch(`${REAL}/v1/exchange/fr01-buy/intents`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ riskScore: 900, offerSanctioned: true, listMinor: 1000000, discountBps: 2000, feeMinor: 20000, maxPriceMinor: 950000, attributes }) });
    check('intent with the new attributes accepted', (await intent({ warrantyYears: 2, giftWrap: true })).status === 202);
    check('intent with a wrong type refused', (await intent({ warrantyYears: 'two' })).status === 400);

    // Investor app: the rules editor lists the new attributes.
    await p.goto('http://127.0.0.1:18185/#/investor/factory'); await p.waitForTimeout(800);
    await p.getByRole('button', { name: /Interpret instruction/ }).click(); await p.waitForTimeout(800);
    await p.getByRole('button', { name: /Continue/ }).first().click(); await p.waitForTimeout(300);
    await p.getByRole('button', { name: /Card \/ fiat credit/ }).click();
    await p.getByRole('button', { name: 'Continue to Bank X' }).click();
    await p.locator('#otp').fill('123456'); await p.getByRole('button', { name: 'Verify' }).click(); await p.waitForTimeout(1300);
    await p.getByRole('button', { name: /^Continue/ }).last().click(); await p.waitForTimeout(800);
    await p.getByText('Custom fields & rules', { exact: true }).click(); await p.waitForTimeout(500);
    await p.getByText(/Attributes you can use in expressions/).click(); await p.waitForTimeout(300);
    t = await p.locator('main').innerText();
    check('editor lists warrantyYears (number) and giftWrap (boolean)', /warrantyYears\s+number/.test(t) && /giftWrap\s+boolean/.test(t));
    check('editor lists built-ins as computed by the exchange', t.includes('computed by the exchange'));
    await p.screenshot({ path: path.join(OUT, 'attributes-editor.png'), fullPage: true });
    check('no page errors', errors.length === 0);
  } catch (e) {
    console.log('FAIL step:', e.message.split('\n')[0]); checks.push(false);
    await p.screenshot({ path: path.join(OUT, 'attributes-failed.png'), fullPage: true });
  }
  await b.close(); s1.close(); s2.close(); process.exitCode = checks.every(Boolean) ? 0 : 1;
})();
