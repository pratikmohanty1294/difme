import { useState } from 'react';
import { Play, Trophy, Zap } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { dateTime, ms, inr } from '../../sim/format';
import type { Bid, BuyRequest } from '../../sim/types';
import { Badge, Card, Empty, FlowTag, PageHeader, StatusBadge, cx } from '../../components/ui';
import { href } from '../../router';
import { SCENARIO } from '../../sim/data';
import { RankingInfo } from '../../components/RankingInfo';

export default function LiveAuction({ focus }: { focus?: string }) {
  const { state, submitRequest } = useEngine();
  const withAuction = state.requests.filter((r) => !r.seeded || r.bids.length);
  const [picked, setPicked] = useState<string | undefined>(focus);
  const req = state.requests.find((r) => r.id === (picked ?? focus)) ?? withAuction[0] ?? state.requests[0];

  return (
    <>
      <PageHeader eyebrow="Exchange · SBBA auction" title="Live auction"
        desc="Each buy request opens a 50 ms SBBA auction. Buying partner agents bid a yield from their own cache; the best score wins provisionally, and ranks 2 and 3 stay on as hot backups for silent failover."
        actions={<>
          <label htmlFor="pick" className="sr-only">Auction</label>
          <select id="pick" className="input w-auto" value={req?.id} onChange={(e) => setPicked(e.target.value)}>
            {state.requests.map((r) => <option key={r.id} value={r.id}>{r.auctionId} · {r.product} · {r.status}</option>)}
          </select>
          <button className="btn-primary" onClick={() => setPicked(submitRequest({ maxPrice: SCENARIO.maxPrice, expiry: '00:30', outcome: 'success', skipConfirm: true }))}><Play size={15} /> Run test auction</button>
        </>} />
      <RankingInfo className="mb-4" />
      {!req ? <Empty title="No auctions yet" /> : <AuctionView req={req} />}
    </>
  );
}

export function AuctionView({ req }: { req: BuyRequest }) {
  const { state } = useEngine();
  const winner = req.bids.find((b) => b.status === 'Won');
  const wNode = state.nodes.find((n) => n.id === req.winnerNodeId);
  const ranked = [...req.bids].sort((a, b) => (a.status === 'Rejected' ? 1 : 0) - (b.status === 'Rejected' ? 1 : 0) || (b.score ?? 0) - (a.score ?? 0));
  const hasRace = req.bids.length > 0;
  const settle = req.timeline.filter((s) => ['PRL', 'POE', 'POC', 'FAIL'].includes(s.phase));

  return (
    <div className="flex flex-col gap-5">
      <div className="card px-5 py-4 flex flex-wrap items-center gap-x-8 gap-y-3">
        <div><p className="label">Auction</p><p className="font-mono font-medium">{req.auctionId}</p></div>
        <div><p className="label">Buy request (POI)</p><p className="font-medium">{inr(req.list)} list · max price {inr(req.maxPrice)} · BIN {req.bin}</p></div>{req.split && <div><p className="label">Quote</p><p className="font-medium tabular">A {inr(req.split.A)} + Y {inr(req.split.Y)} + F {inr(req.split.F)} = B {inr(req.split.B)}</p></div>}
        <div><p className="label">L3 risk score</p><p className="font-medium tabular">{req.riskScore} <span className="text-xs text-exchange-600">≥ {state.config.minRiskScore}</span></p></div>
        <div><p className="label">Opened</p><p className="font-medium">{dateTime(req.createdAt)}</p></div>
        <div className="ml-auto flex items-center gap-2"><StatusBadge status={req.status} />{req.route && <Badge tone="purple">{req.route}</Badge>}</div>
      </div>

      <Card title="Race timeline" sub="Simulated exchange time from intent submission (ms)">
        {hasRace ? <RaceTrack req={req} /> : <p className="text-sm text-ink-500">This is an imported record with no bid detail. Run a test auction to see a live race.</p>}
      </Card>

      <div className="flex flex-col gap-5">
        <Card title="Bids and SBBA scores" sub="Score = w1 × U + w2 × R − w3 × L/Lmax, with U = (d·P − Y) / (d·P): a lower yield ask means a bigger buyer saving" pad={false}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr>
                <th className="th">Bid</th><th className="th">Node</th><th className="th">Flow</th><th className="th text-right">Yield ask</th><th className="th text-right">Reliab.</th>
                <th className="th text-right">Latency</th><th className="th">ZK</th><th className="th">Score breakdown</th><th className="th text-right">Score</th><th className="th">Status</th>
              </tr></thead>
              <tbody>
                {ranked.map((b) => <BidRow key={b.id + b.nodeId} b={b} />)}
                {ranked.length === 0 && <tr><td colSpan={10} className="td text-ink-500">Waiting for bids on the multicast channel…</td></tr>}
              </tbody>
            </table>
          </div>
        </Card>

        <div className="grid gap-5 lg:grid-cols-2">
          <Card title={<span className="flex items-center gap-2"><Trophy size={15} className="text-amber-600" /> Winner</span>}>
            {winner && wNode ? (
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between"><p className="font-semibold">{wNode.label}</p><FlowTag flow={wNode.flow} /></div>
                <p className="text-sm text-ink-600">{wNode.owner} · {wNode.region}</p>
                <p className="text-3xl font-display font-semibold tabular">{winner.score?.toFixed(3)}</p>
                <p className="text-xs text-ink-500">Selection is provisional until the merchant's PSP approves the charge (two-phase commit). Ties go to the lower yield ask.</p>
                {(req.failedNodeIds?.length ?? 0) > 0 && <p className="text-xs text-ink-600 rounded-md bg-amber-50 p-2.5">Failover: {req.failedNodeIds!.join(', ')} declined at the merchant and were slashed; the order went to the next bidder without the buyer noticing.</p>}{req.route === 'Guarantee Fund backstop' && <p className="text-xs text-ink-600 rounded-md bg-red-50 p-2.5">Every bidder declined, so DiscountAI bought at list price with its corporate card and the Guarantee Fund covered the difference.</p>}
              </div>
            ) : req.status === 'Timed Out' ? <p className="text-sm text-ink-600">No eligible bid. The intent timed out.</p> : <p className="text-sm text-ink-500 live-dot">Scoring at batch close…</p>}
          </Card>
          <Card title="Settlement path" sub="Buyer leg: Agent PA/PG · purchase leg: Merchant M via Merchant PSP">
            {settle.length ? (
              <ol className="flex flex-col gap-2.5">
                {settle.map((s, i) => (
                  <li key={i} className="rise flex gap-2.5 text-[13px]">
                    <span className={cx('mt-0.5 rounded px-1.5 font-mono text-[10.5px] h-fit', s.phase === 'FAIL' ? 'bg-red-50 text-red-700' : 'bg-ink-100 text-ink-600')}>{s.phase}</span>
                    <span className="text-ink-800">{s.label}{s.detail && <span className="block text-xs text-ink-500">{s.detail}</span>}</span>
                  </li>
                ))}
              </ol>
            ) : <p className="text-sm text-ink-500">Starts after the winner signs Proof-of-Lock.</p>}
            {req.proofs.length > 0 && <a href={href('audit', req.id)} className="btn-secondary mt-4 w-full">Open proof chain ({req.proofs.length}/5)</a>}
          </Card>
        </div>
      </div>
    </div>
  );
}

function BidRow({ b }: { b: Bid }) {
  const p = b.scoreParts;
  return (
    <tr className={cx(b.status === 'Won' && 'bg-exchange-50', b.status === 'Rejected' && 'text-ink-400')}>
      <td className="td font-mono text-xs whitespace-nowrap">{b.id}</td>
      <td className="td font-medium whitespace-nowrap">{b.nodeId}</td>
      <td className="td"><FlowTag flow={b.flow} /></td>
      <td className="td text-right tabular whitespace-nowrap">{inr(b.yieldAsk)} <span className="text-xs text-ink-400">{b.yieldAskPct}%</span></td>
      <td className="td text-right tabular">{Math.round(b.reliability * 100)}%</td>
      <td className="td text-right tabular">{b.latencyMs} ms</td>
      <td className="td">{b.zkProof === 'verified' ? <Badge tone="purple">verified</Badge> : <span className="text-xs text-ink-400">n/a</span>}</td>
      <td className="td font-mono text-[11.5px] whitespace-nowrap text-ink-600">
        {b.status === 'Rejected' ? <span className="font-sans text-red-700">{b.rejectReason}</span> : p ? `${p.w.w1}×${p.utility.toFixed(3)} + ${p.w.w2}×${b.reliability.toFixed(2)} − ${p.w.w3}×${(p.w.w3 ? p.latPart / p.w.w3 : 0).toFixed(2)}` : '—'}
      </td>
      <td className="td text-right tabular font-semibold">{b.status === 'Rejected' ? '—' : b.score?.toFixed(3)}</td>
      <td className="td"><Badge tone={b.status === 'Won' ? 'green' : b.status === 'Backup' ? 'blue' : b.status === 'Outbid' ? 'gray' : b.status === 'Rejected' ? 'red' : 'amber'}>{b.status === 'Backup' ? `Backup #${b.rank}` : b.status}</Badge></td>
    </tr>
  );
}

function RaceTrack({ req }: { req: BuyRequest }) {
  const max = 60;
  const pos = (t: number) => `${Math.min(100, (t / max) * 100)}%`;
  const marks = [
    { t: 0, label: 'Intent · risk · publish', sub: '0–2.6 ms', align: 'start' },
    { t: 45, label: 'Batch close', sub: '50 ms window', align: 'center' },
    { t: 50.2, label: 'Provisional winner (POL)', sub: '50.2 ms', align: 'center' },
  ];
  const progress = Math.max(0, ...req.timeline.map((s) => Math.min(s.t, max)));
  const reached = (t: number) => progress >= t;
  return (
    <div className="overflow-x-auto">
      <div className="relative min-w-[640px] h-36 mx-2">
        <div className="absolute left-0 right-0 top-16 h-1 rounded-full bg-ink-100" />
        <div className="absolute top-16 h-1 rounded-full bg-exchange-600 transition-all duration-500" style={{ left: 0, width: pos(progress) }} />
        <div className="absolute top-[52px] h-7 border-r border-dashed border-ink-300" style={{ left: pos(2.6), width: `calc(${pos(50)} - ${pos(2.6)})` }} />
        <span className="absolute top-[118px] text-[10.5px] text-ink-500 whitespace-nowrap -translate-x-1/2" style={{ left: pos(26) }}>← 50 ms batch window · bids priced from internal cache →</span>
        {marks.map((m) => (
          <div key={m.label} className={cx('absolute top-14 flex flex-col', m.align === 'start' ? 'items-start -translate-x-2.5' : 'items-center -translate-x-1/2')} style={{ left: pos(m.t) }}>
            <span className={cx('h-5 w-5 rounded-full border-2 border-white shadow', reached(m.t) ? 'bg-exchange-600' : 'bg-ink-200')} />
            <span className="mt-1.5 text-[11px] text-ink-700 whitespace-nowrap">{m.label}</span>
            <span className="text-[10.5px] font-mono text-ink-400">{m.sub}</span>
          </div>
        ))}
        {req.bids.map((b) => (
          <div key={b.id + b.nodeId} className="absolute top-3 -translate-x-1/2 flex flex-col items-center" style={{ left: pos(b.receivedAtMs) }} title={`${b.nodeId} bid at ${ms(b.receivedAtMs)}`}>
            <span className={cx('rounded px-1.5 py-0.5 text-[10.5px] font-medium whitespace-nowrap', b.status === 'Won' ? 'bg-exchange-600 text-white' : b.status === 'Rejected' ? 'bg-red-50 text-red-700' : 'bg-investor-100 text-investor-600')}>
              <Zap size={10} className="inline -mt-0.5" /> {b.nodeId.replace('IAgent-', '')}
            </span>
            <span className="w-px h-6 bg-ink-300" />
          </div>
        ))}
      </div>
    </div>
  );
}
