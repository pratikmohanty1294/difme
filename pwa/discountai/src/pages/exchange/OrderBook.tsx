import { useEngine } from '../../sim/engine';
import { time, inr } from '../../sim/format';
import { Badge, Card, FlowTag, PageHeader, StatusBadge } from '../../components/ui';
import { href } from '../../router';

export default function OrderBook() {
  const { state } = useEngine();
  const intents = state.requests;
  const bids = state.requests.flatMap((r) => r.bids.map((b) => ({ ...b, auctionId: r.auctionId, reqId: r.id, at: r.createdAt })));
  const nodeOf = (id: string) => state.nodes.find((n) => n.id === id);

  return (
    <>
      <PageHeader eyebrow="Exchange · central limit order book" title="Order book"
        desc="Two live books: buyer intents waiting for a credential, and buying partner bids received over the Aeron multicast channel. Matched rows name their counterpart." />
      <div className="grid gap-5 2xl:grid-cols-2">
        <Card title="Buyer intents" sub={`${intents.length} intents · gRPC/HTTP2 intake`} pad={false}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr><th className="th">Intent / auction</th><th className="th">Asset class</th><th className="th text-right">List P</th><th className="th text-right">Max price</th><th className="th text-right">L3 risk</th><th className="th">Status</th><th className="th">Matched bid</th><th className="th">Time</th></tr></thead>
              <tbody>
                {intents.map((r) => {
                  const won = r.bids.find((b) => b.status === 'Won');
                  return (
                    <tr key={r.id} className="hover:bg-ink-50">
                      <td className="td"><a href={href('exchange', r.id)} className="font-mono text-xs hover:underline">{r.id}</a><span className="block font-mono text-[11px] text-ink-500">{r.auctionId}</span></td>
                      <td className="td whitespace-nowrap">Bank X · BIN {r.bin}</td>
                      <td className="td text-right tabular">{inr(r.list)}</td>
                      <td className="td text-right tabular">{inr(r.maxPrice)}</td>
                      <td className="td text-right tabular">{r.riskScore}</td>
                      <td className="td"><StatusBadge status={r.status} /></td>
                      <td className="td font-mono text-xs">{won ? `${won.id} · ${won.nodeId}` : r.winnerNodeId ?? '—'}</td>
                      <td className="td font-mono text-xs text-ink-500">{time(r.createdAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Buying partner bids" sub={`${bids.length} bids · FIX SBE ~100 bytes each`} pad={false}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr><th className="th">Bid</th><th className="th">Node</th><th className="th">Flow</th><th className="th">Asset class</th><th className="th text-right">Yield ask</th><th className="th text-right">Reliability</th><th className="th">BIN</th><th className="th">Status</th><th className="th">Auction</th></tr></thead>
              <tbody>
                {bids.length === 0 && <tr><td className="td text-ink-500" colSpan={9}>No bids yet. Run a request from the buyer agent or the live auction page.</td></tr>}
                {bids.map((b) => (
                  <tr key={`${b.auctionId}-${b.id}-${b.nodeId}`} className="hover:bg-ink-50">
                    <td className="td font-mono text-xs">{b.id}</td>
                    <td className="td whitespace-nowrap">{b.nodeId}</td>
                    <td className="td"><FlowTag flow={b.flow} /></td>
                    <td className="td whitespace-nowrap">{b.assetClass}</td>
                    <td className="td text-right tabular">{inr(b.yieldAsk)}</td>
                    <td className="td text-right tabular">{Math.round(b.reliability * 100)}%</td>
                    <td className="td font-mono text-xs">{nodeOf(b.nodeId)?.bin ?? '—'}</td>
                    <td className="td"><Badge tone={b.status === 'Won' ? 'green' : b.status === 'Backup' ? 'blue' : b.status === 'Rejected' ? 'red' : b.status === 'Outbid' ? 'gray' : 'amber'}>{b.status === 'Won' ? `Matched · ${b.reqId}` : b.status}</Badge></td>
                    <td className="td font-mono text-[11px] text-ink-500">{b.auctionId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}
