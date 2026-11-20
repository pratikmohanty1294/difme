import { Globe2, Server } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { Badge, Card, FlowTag, Meter, PageHeader, Stat } from '../../components/ui';

const REGIONS = [
  { id: 'US-East-1 · Virginia', role: 'Card and bank agents' },
  { id: 'EU-West-1 · Frankfurt', role: 'Crypto and FX agents' },
  { id: 'AP-South-1 · Mumbai', role: 'UPI, RWA and Indian card agents' },
] as const;

export default function Network() {
  const { state } = useEngine();
  const done = state.requests.filter((r) => r.status === 'Success').length;
  const failed = state.requests.filter((r) => r.status === 'Refunded').length;
  const timedOut = state.requests.filter((r) => r.status === 'Timed Out').length;

  return (
    <>
      <PageHeader eyebrow="Exchange · connectivity and compliance" title="Network and nodes"
        desc="A three-region Aeron cluster. Node health, deposits and the last passkey re-check decide who may bid; reliability feeds the SBBA score." />
      <div className="grid gap-4 grid-cols-2 xl:grid-cols-4 mb-5">
        <Stat label="Match latency · P99" value="3.8 ms" sub="Target < 5 ms" tone="good" />
        <Stat label="Provisional winner" value="50.2 ms" sub="SBBA window 50 ms · backups ranked" />
        <Stat label="Event stream" value="612k/s" sub="Redpanda · capacity 1M events/s" />
        <Stat label="Outcomes today" value={`${done} / ${failed} / ${timedOut}`} sub="Settled / refunded / timed out" />
      </div>
      <Card title={<span className="flex items-center gap-2"><Globe2 size={16} /> Aeron channel</span>} sub="UDP multicast 234.1.1.1:40123 · stream 1001 · FIX SBE ~100 bytes per bid" className="mb-5">
        <div className="grid gap-4 md:grid-cols-3">
          {REGIONS.map((r) => {
            const nodes = state.nodes.filter((n) => n.region === r.id);
            return (
              <div key={r.id} className="rounded-xl border border-ink-200 p-4">
                <div className="flex items-center gap-2"><Server size={15} className="text-exchange-600" /><p className="font-medium">{r.id}</p></div>
                <p className="text-xs text-ink-500 mt-0.5 mb-3">{r.role} · {nodes.length} node{nodes.length === 1 ? '' : 's'}</p>
                <div className="flex flex-col gap-3">
                  {nodes.map((n) => (
                    <div key={n.id} className="rounded-lg bg-ink-50 p-3">
                      <div className="flex items-center justify-between gap-2"><span className="font-medium text-sm">{n.label}</span><FlowTag flow={n.flow} /></div>
                      <p className="text-xs text-ink-500 mt-0.5">{n.owner}{n.isYou ? '' : ''}</p>
                      <div className="mt-2.5 grid grid-cols-[auto_1fr_auto] items-center gap-x-2 gap-y-1.5 text-xs">
                        <span className="text-ink-500">Reliability</span><Meter value={n.reliability * 100} tone={n.reliability > 0.9 ? 'green' : 'amber'} /><span className="tabular w-11 text-right">{(n.reliability * 100).toFixed(1)}%</span>
                        <span className="text-ink-500">ZK accept</span><Meter value={n.zkAcceptance * 100} tone="green" /><span className="tabular w-11 text-right">{n.zkCapable ? `${(n.zkAcceptance * 100).toFixed(1)}%` : 'n/a'}</span>
                      </div>
                      <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11.5px] text-ink-600">
                        <span>{n.activeMandates} mandates</span><span>queue {n.queueDepth}</span><span>{n.latencyMs} ms</span><span>deposit ₹{n.depositInr.toLocaleString('en-IN')}</span><span>VPP {n.vppAgeDays}d</span>
                        {n.fraudFlags > 0 ? <Badge tone="red">{n.fraudFlags} fraud flag</Badge> : <span className="text-exchange-600">no fraud flags</span>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </Card>
      <p className="text-xs text-ink-500">Reliability is a 30-day EWMA of successful settlements. New nodes start at {Math.round(state.config.newNodeReliability * 100)}%; declines at the merchant (each also slashes the deposit), time-outs and ZK proof failures lower it. Fraud flags come from the vector-similarity engine.</p>
    </>
  );
}
