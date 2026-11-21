import { useState } from 'react';
import { Download, FileJson, FileSpreadsheet } from 'lucide-react';
import { Callout, Card, PageHeader } from '../../components/ui';

const today = () => new Date(Date.now() + 5.5 * 3600_000).toISOString().slice(0, 10);   // IST date

/**
 * Exchange operator, live builds: reports as files to share with regulators, partners and auditors (decided
 * 2026-11-21: reporting is file based, no regulator node). The JSON file is signed by the exchange operator key.
 */
export default function Reports() {
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());
  const bad = !from || !to || to < from;
  const url = (format: 'csv' | 'json') => `/api/v1/exchange/reports/orders?format=${format}&from=${from}&to=${to}`;
  return (
    <>
      <PageHeader eyebrow="Exchange OME · reporting" title="Reports"
        desc="Download order reports to share as files with regulators, partners and auditors. Days are Indian Standard Time." />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <Card title="Orders report" sub="Every order closed in the period: amounts, outcome, refunds, escrow, settings version and evidence status">
          <div className="flex flex-wrap gap-4">
            <label className="text-sm"><span className="font-medium">From</span><input aria-label="From" type="date" className="input mt-1" value={from} max={today()} onChange={(e) => setFrom(e.target.value)} /></label>
            <label className="text-sm"><span className="font-medium">To</span><input aria-label="To" type="date" className="input mt-1" value={to} max={today()} onChange={(e) => setTo(e.target.value)} /></label>
          </div>
          {bad && <p className="mt-2 text-xs text-red-700">“To” must be on or after “From”.</p>}
          <div className="mt-4 flex flex-wrap gap-2">
            <a className={bad ? 'btn-secondary pointer-events-none opacity-50' : 'btn-secondary'} href={url('csv')} download aria-disabled={bad}>
              <FileSpreadsheet size={15} /> Download CSV</a>
            <a className={bad ? 'btn-primary pointer-events-none opacity-50' : 'btn-primary'} href={url('json')} download aria-disabled={bad}>
              <FileJson size={15} /> Download signed JSON</a>
          </div>
          <p className="mt-3 text-xs text-ink-600">
            CSV opens in any spreadsheet. The JSON file carries a SHA-256 of its rows and the exchange operator's Ed25519
            signature, so the recipient can check it came from the exchange unchanged.
          </p>
        </Card>
        <Card title="How a recipient verifies the JSON file">
          <ol className="list-decimal pl-5 text-sm text-ink-700 flex flex-col gap-1.5">
            <li>Get the exchange's public keys (<span className="font-mono text-xs">GET /v1/exchange/keys</span>), or a copy you were given.</li>
            <li>Run <span className="font-mono text-xs">python3 verify-report.py &lt;report.json&gt; &lt;keys&gt;</span> (in the repository under <span className="font-mono text-xs">ci/</span>).</li>
            <li>It recomputes the SHA-256 of the rows and checks the signature with the key named in the file; any edited value fails.</li>
          </ol>
          <div className="mt-3"><Callout tone="info" title="What the report doesn't contain">No buyer or card details: the exchange never holds them. Amounts are in paise (minor units).</Callout></div>
          <a className="btn-ghost mt-3 inline-flex text-xs" href="/api/v1/exchange/keys" download="difme-exchange-keys.json"><Download size={14} /> Download the public keys</a>
        </Card>
      </div>
    </>
  );
}
