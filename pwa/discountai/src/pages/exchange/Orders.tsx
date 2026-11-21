import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { exchange, ExchangeError, type OrdersView } from '../../api/exchange';
import { href } from '../../router';
import { Badge, Callout, Card, PageHeader, Stat, type Tone } from '../../components/ui';

const inr = (minor?: number) => (minor === undefined ? '—' : `₹${(minor / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`);
const STATES = ['NO_MATCH', 'AWARDED', 'FAILED', 'BOUGHT', 'DELIVERED', 'PARTLY_RETURNED', 'RETURNED', 'SETTLED'] as const;
const LABEL: Record<string, string> = {
  NO_MATCH: 'No match', AWARDED: 'Awarded', FAILED: 'Failed · refunded', BOUGHT: 'Bought', DELIVERED: 'Delivered',
  PARTLY_RETURNED: 'Partly returned', RETURNED: 'Returned', SETTLED: 'Settled',
};
const TONE: Record<string, Tone> = {
  NO_MATCH: 'gray', AWARDED: 'blue', FAILED: 'red', BOUGHT: 'blue', DELIVERED: 'green', PARTLY_RETURNED: 'amber', RETURNED: 'amber', SETTLED: 'green',
};

/** Exchange operator, live builds: every order the exchange holds, newest first, from its own records. */
export default function Orders() {
  const [view, setView] = useState<OrdersView>();
  const [state, setState] = useState<string>();
  const [err, setErr] = useState<string>();
  const load = () => exchange.orders(state).then((v) => { setView(v); setErr(undefined); },
    (e) => setErr(e instanceof ExchangeError && e.status === 0 ? 'The exchange is unreachable.' : String(e instanceof Error ? e.message : e)));
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [state]);
  const t = view?.totals;

  return (
    <>
      <PageHeader eyebrow="Exchange OME · operations" title="Orders"
        desc="Every order the exchange holds, newest first: the auction result, the purchase over the rails, delivery, returns and settlement. Refreshes every 5 seconds."
        actions={<button className="btn-secondary" onClick={load}><RefreshCw size={14} /> Refresh</button>} />
      {err && <Callout tone="warn" title="Couldn't load orders">{err}</Callout>}
      {t && (
        <div className="grid gap-4 grid-cols-2 xl:grid-cols-5 mb-5">
          <Stat label="Orders" value={t.orders} sub={`${t.byState.DELIVERED ?? 0} delivered · ${t.byState.SETTLED ?? 0} settled`} />
          <Stat label="Bought (B)" value={inr(t.boughtMinor)} sub={`buyers saved ${inr(t.buyersSavedMinor)}`} tone="good" />
          <Stat label="Platform fees (F)" value={inr(t.feesMinor)} sub="net of reversals" />
          <Stat label="Partner yield (Y)" value={inr(t.partnerYieldMinor)} sub="net of voided yield" />
          <Stat label="Refunded" value={inr(t.refundedMinor)} sub={`${(t.byState.RETURNED ?? 0) + (t.byState.PARTLY_RETURNED ?? 0)} returns · ${t.byState.FAILED ?? 0} failed`} tone={t.refundedMinor ? 'warn' : 'neutral'} />
        </div>
      )}
      <div className="flex flex-wrap gap-2 mb-3" role="group" aria-label="Filter by state">
        <button className={state ? 'btn-ghost text-xs' : 'btn-secondary text-xs'} onClick={() => setState(undefined)}>All</button>
        {STATES.map((s) => <button key={s} className={state === s ? 'btn-secondary text-xs' : 'btn-ghost text-xs'} onClick={() => setState(s)}>{LABEL[s]} {t?.byState[s] ? `(${t.byState[s]})` : ''}</button>)}
      </div>
      <Card title="Order list" sub={view ? `${view.orders.length} shown` : '—'} pad={false}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr>
              <th className="th">Closed</th><th className="th">Auction</th><th className="th">Merchant</th><th className="th">State</th>
              <th className="th text-right">Buyer pays B</th><th className="th text-right">A</th><th className="th text-right">Y</th><th className="th text-right">F</th>
              <th className="th">Bought by</th><th className="th text-right">Refunded</th><th className="th">Escrow</th>
            </tr></thead>
            <tbody>
              {view?.orders.map((o) => (
                <tr key={o.auctionId} className="hover:bg-ink-50">
                  <td className="td tabular text-xs">{o.closedAtMs ? new Date(o.closedAtMs).toLocaleString('en-IN', { dateStyle: 'short', timeStyle: 'medium' }) : '—'}</td>
                  <td className="td font-mono text-xs"><a className="hover:underline" href={href('audit', String(o.auctionId))}>{o.auctionId}</a></td>
                  <td className="td">{o.merchant ?? '—'}</td>
                  <td className="td"><Badge tone={TONE[o.state] ?? 'gray'}>{LABEL[o.state] ?? o.state}</Badge></td>
                  <td className="td text-right tabular">{inr(o.bMinor)}</td>
                  <td className="td text-right tabular">{inr(o.aMinor)}</td>
                  <td className="td text-right tabular">{inr(o.yieldMinor)}</td>
                  <td className="td text-right tabular">{inr(o.feeMinor)}</td>
                  <td className="td text-xs">{o.boughtBy !== undefined ? `node ${o.boughtBy}${o.failovers ? ` (+${o.failovers} failover)` : ''}` : o.winnerNode !== undefined ? `won by ${o.winnerNode}` : '—'}{o.mandateId ? <span className="block text-ink-500">mandate {o.mandateId}</span> : null}</td>
                  <td className="td text-right tabular">{o.refundedMinor ? inr(o.refundedMinor) : '—'}</td>
                  <td className="td text-xs">{o.escrow ? `${o.escrow.state.toLowerCase().replace('_', ' ')} · ${inr(o.escrow.releasedMinor)} out` : '—'}</td>
                </tr>
              ))}
              {view && view.orders.length === 0 && <tr><td className="td text-ink-500" colSpan={11}>No orders{state ? ` in “${LABEL[state]}”` : ''} yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
