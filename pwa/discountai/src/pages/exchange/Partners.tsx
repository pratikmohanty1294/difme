import { useEffect, useState } from 'react';
import { exchange, ExchangeError, type PartnerRow } from '../../api/exchange';
import { Badge, Callout, Card, PageHeader } from '../../components/ui';

const inr = (minor: number) => `₹${(minor / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;

/** Exchange operator, live builds: the buying partners the exchange knows through their mandates. */
export default function Partners() {
  const [rows, setRows] = useState<PartnerRow[]>();
  const [err, setErr] = useState<string>();
  useEffect(() => {
    const load = () => exchange.partnerList().then((p) => { setRows(p.partners); setErr(undefined); },
      (e) => setErr(e instanceof ExchangeError && e.status === 0 ? 'The exchange is unreachable.' : String(e instanceof Error ? e.message : e)));
    load(); const t = setInterval(load, 10_000); return () => clearInterval(t);
  }, []);
  return (
    <>
      <PageHeader eyebrow="Exchange OME · operations" title="Buying partners"
        desc="Partners with mandates on this exchange: their tier (cold start until 3 verified deliveries with reputation ≥ 80), reputation as the exchange tracks it, and what their mandates have won and hold." />
      {err && <Callout tone="warn" title="Couldn't load partners">{err}</Callout>}
      <Card title="Partners" sub={rows ? `${rows.length} partners` : '—'} pad={false}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr>
              <th className="th">Partner</th><th className="th">Node</th><th className="th">Tier</th><th className="th text-right">Verified deliveries</th>
              <th className="th text-right">Reputation</th><th className="th text-right">Mandates</th><th className="th text-right">Wins</th>
              <th className="th text-right">Yield earned</th><th className="th text-right">Budget left</th><th className="th text-right">Deposits</th>
            </tr></thead>
            <tbody>
              {rows?.map((p) => (
                <tr key={p.nodeRef} className="hover:bg-ink-50">
                  <td className="td">{p.partnerRef}</td>
                  <td className="td font-mono text-xs">{p.nodeRef}</td>
                  <td className="td"><Badge tone={p.tier === 'COLD_START' ? 'amber' : 'green'}>{p.tier.replace('_', ' ').toLowerCase()}</Badge></td>
                  <td className="td text-right tabular">{p.verifiedDeliveries}</td>
                  <td className="td text-right tabular">{p.reputation === null ? 'not yet' : p.reputation.toFixed(1)}</td>
                  <td className="td text-right tabular">{p.activeMandates} / {p.mandates}</td>
                  <td className="td text-right tabular">{p.awards}</td>
                  <td className="td text-right tabular">{inr(p.yieldEarnedMinor)}</td>
                  <td className="td text-right tabular">{inr(p.budgetLeftMinor)}</td>
                  <td className="td text-right tabular">{inr(p.depositMinor)}</td>
                </tr>
              ))}
              {rows && rows.length === 0 && <tr><td className="td text-ink-500" colSpan={10}>No partners have registered a mandate yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
