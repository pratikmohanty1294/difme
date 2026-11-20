import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ArrowDownRight, ArrowUpRight, Plus, Radio } from 'lucide-react';
import { YIELD_SERIES } from '../../sim/data';
import { useEngine } from '../../sim/engine';
import { time, inr, inr2 } from '../../sim/format';
import { Badge, Card, FlowTag, Meter, PageHeader, Stat, cx } from '../../components/ui';
import { href } from '../../router';
import type { FeedItem } from '../../sim/types';

const feedTone: Record<FeedItem['kind'], string> = {
  bid: 'text-ink-500', won: 'text-exchange-600', outbid: 'text-amber-700', settled: 'text-exchange-600', refund: 'text-audit-600', mandate: 'text-investor-600', config: 'text-ink-500', failed: 'text-red-700',
};

export default function CommandCenter() {
  const { state } = useEngine();
  const mandates = state.mandates;
  const live = mandates.filter((m) => m.status === 'LIVE');
  const yieldTotal = mandates.reduce((a, m) => a + m.yieldEarned, 0);
  const limit = live.reduce((a, m) => a + m.limit, 0);
  const used = live.reduce((a, m) => a + m.utilised, 0);
  const me = state.nodes.find((n) => n.isYou)!;
  const myFeed = state.feed.filter((f) => f.nodeId === me.id || f.kind === 'mandate' || f.kind === 'config');
  const wins = myFeed.filter((f) => f.kind === 'won').length;
  const races = myFeed.filter((f) => f.kind === 'won' || f.kind === 'outbid').length;

  return (
    <>
      <PageHeader eyebrow="Buying Partner" title="Buying Partner command center"
        desc="Commerce yield your agent earns by buying, within your budget, items shipped to matched buyers, with your idle Bank X credit line, UPI reserve or tokens, repaid A + Y before your card is due."
        actions={<a className="btn-primary" href={href('investor/factory')}><Plus size={15} /> New smart mandate</a>} />

      <div className="grid gap-4 grid-cols-2 xl:grid-cols-4 mb-5">
        <Stat label="Total commerce yield" value={inr2(yieldTotal)} sub="+12.4% vs last week" tone="good" />
        <Stat label="Active mandates" value={<>{live.length}<span className="text-ink-400 text-lg">/{mandates.length}</span></>} sub={`${mandates.length - live.length} paused or revoked`} />
        <Stat label="Capital utilisation" value={`${limit ? Math.round((used / limit) * 100) : 0}%`} sub={`${inr(used)} of ${inr(limit)} allocated`} />
        <Stat label="Race win rate" value={races ? `${Math.round((wins / races) * 100)}%` : '—'} sub={`${wins} won of ${races} races this session`} />
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-5 min-w-0">
          <Card title="Yield by settlement flow" sub="Last 7 days · inr" right={<div className="flex gap-3 text-xs text-ink-600">
            <span className="flex items-center gap-1.5"><i className="h-2 w-2 rounded-sm bg-investor-600" />Flow A card</span>
            <span className="flex items-center gap-1.5"><i className="h-2 w-2 rounded-sm bg-audit-600" />Flow B Finternet</span>
            <span className="flex items-center gap-1.5"><i className="h-2 w-2 rounded-sm bg-buyer-600" />Flow C UPI</span></div>}>
            <div className="h-64 -ml-3">
              <ResponsiveContainer>
                <AreaChart data={YIELD_SERIES} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="#edf1f6" vertical={false} />
                  <XAxis dataKey="d" tickLine={false} axisLine={false} tick={{ fill: '#64728a', fontSize: 12 }} />
                  <YAxis tickLine={false} axisLine={false} tick={{ fill: '#64728a', fontSize: 12 }} width={44} tickFormatter={(v) => `$${v}`} />
                  <Tooltip formatter={(v: number) => inr(v)} contentStyle={{ borderRadius: 8, border: '1px solid #dbe1ea', fontSize: 12 }} />
                  <Area type="monotone" dataKey="flowC" name="Flow C" stackId="1" stroke="#b4520c" fill="#fbe9d9" />
                  <Area type="monotone" dataKey="flowA" name="Flow A" stackId="1" stroke="#2453c2" fill="#e0e8fb" />
                  <Area type="monotone" dataKey="flowB" name="Flow B" stackId="1" stroke="#6b3fb5" fill="#ece4fa" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </Card>

          <Card title="Mandate performance" sub="Token performance per smart mandate" pad={false}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead><tr><th className="th">Mandate</th><th className="th">Flow</th><th className="th text-right">Yield earned</th><th className="th w-48">Utilisation</th><th className="th">Status</th></tr></thead>
                <tbody>
                  {mandates.map((m) => (
                    <tr key={m.id} className="hover:bg-ink-50">
                      <td className="td"><a href={href('investor/mandates', m.id)} className="font-medium text-ink-900 hover:underline">{m.name}</a><span className="block font-mono text-[11px] text-ink-500">{m.id}</span></td>
                      <td className="td"><FlowTag flow={m.flow} /></td>
                      <td className="td text-right tabular">{inr2(m.yieldEarned)}</td>
                      <td className="td"><div className="flex items-center gap-2"><Meter value={(m.utilised / m.limit) * 100} tone="blue" /><span className="text-xs tabular text-ink-600 w-9">{Math.round((m.utilised / m.limit) * 100)}%</span></div></td>
                      <td className="td"><Badge tone={m.status === 'LIVE' ? 'green' : m.status === 'Paused' ? 'amber' : 'red'}>{m.status}</Badge></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </div>

        <div className="flex flex-col gap-5 min-w-0">
          <Card title={<span className="flex items-center gap-2"><Radio size={15} className="text-exchange-600" /> Live race feed</span>} sub="Your agent's bids, wins and settlements" right={<Badge tone="green" dot>Live</Badge>} pad={false}>
            <ul className="max-h-[360px] overflow-y-auto divide-y divide-ink-100">
              {myFeed.slice(0, 30).map((f) => (
                <li key={f.id} className="rise flex items-start gap-3 px-5 py-2.5 text-[13px]">
                  <span className="font-mono text-[11px] text-ink-400 pt-0.5 tabular">{time(f.at)}</span>
                  <span className={cx('flex-1 min-w-0', feedTone[f.kind])}>
                    {f.kind === 'won' || f.kind === 'settled' ? <ArrowUpRight size={13} className="inline mr-1" /> : f.kind === 'outbid' || f.kind === 'failed' ? <ArrowDownRight size={13} className="inline mr-1" /> : null}
                    <span className="text-ink-800">{f.text}</span>
                  </span>
                  {f.amount !== undefined && <span className="font-medium tabular text-exchange-600">+{inr2(f.amount)}</span>}
                </li>
              ))}
            </ul>
          </Card>

          <Card title="Your agent node" sub="As the exchange sees it">
            <div className="flex items-center justify-between mb-3">
              <div><p className="font-medium">{me.label}</p><p className="text-xs text-ink-500">{me.region} · {me.assetClass}</p></div>
              {me.online ? <Badge tone="green" dot>LIVE · listening</Badge> : <Badge tone="amber">Card node offline</Badge>}
            </div>
            {!me.online && <p className="mb-3 text-xs text-amber-800">Deploy a Bank X card mandate in the smart mandate factory to bring this node into Bank X auctions.</p>}
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div><p className="label">Reliability (30d EWMA)</p><p className="text-lg font-semibold tabular">{(me.reliability * 100).toFixed(1)}%</p></div>
              <div><p className="label">Yield ask</p><p className="text-lg font-semibold tabular">{me.yieldAskPct.toFixed(1)}% of A</p></div>
              <div><p className="label">ZK proof acceptance</p><p className="text-lg font-semibold tabular">{(me.zkAcceptance * 100).toFixed(1)}%</p></div>
              <div><p className="label">Bid latency · deposit</p><p className="text-lg font-semibold tabular">{me.latencyMs} ms · ₹{me.depositInr.toLocaleString('en-IN')}</p></div>
            </div>
            <p className="mt-3 text-xs text-ink-500">Bids are priced from the agent's internal cache only; no external API calls during the auction window.</p>
          </Card>
        </div>
      </div>
    </>
  );
}
