import { useMemo, useState } from 'react';
import { RotateCcw, Save } from 'lucide-react';
import { DEFAULT_CONFIG, SCENARIO, SPLIT } from '../../sim/data';
import { useEngine } from '../../sim/engine';
import { dateTime, inr } from '../../sim/format';
import { rankBids, scoreBid } from '../../sim/sbba';
import type { AuctionConfig as Cfg, Bid, Flow } from '../../sim/types';
import { Badge, Callout, Card, FlowTag, PageHeader, cx } from '../../components/ui';

const FLOWS: { f: Flow; name: string }[] = [{ f: 'A', name: 'Card (Bank X agent token)' }, { f: 'B', name: 'USDC / RWA' }, { f: 'C', name: 'UPI Reserve Pay' }];

export default function AuctionConfig() {
  const { state, setConfig } = useEngine();
  const [draft, setDraft] = useState<Cfg>(state.config);
  const dirty = JSON.stringify(draft) !== JSON.stringify(state.config);
  const sums = FLOWS.map(({ f }) => +(draft.weights[f].w1 + draft.weights[f].w2 + draft.weights[f].w3).toFixed(2));
  const valid = sums.every((s) => Math.abs(s - 1) < 0.001);

  // Preview: score the v4.1 SD-C1 reference card bidders (A = ₹8,000) under current vs draft weights.
  const preview = useMemo(() => {
    const ref: Bid[] = state.nodes.filter((n) => n.flow === 'A').map((n) => ({
      id: n.id, nodeId: n.id, flow: n.flow, assetClass: n.assetClass, yieldAsk: Math.round((SPLIT.A * n.yieldAskPct) / 100), yieldAskPct: n.yieldAskPct,
      reliability: n.reliability, latencyMs: n.latencyMs, zkProof: 'n/a', receivedAtMs: 0, status: 'Received',
    }));
    const score = (cfg: Cfg) => rankBids(ref.map((b) => ({ ...b, score: scoreBid(b, cfg, SPLIT.discount).score })));
    return { now: score(state.config), next: score(draft) };
  }, [draft, state.config, state.nodes]);

  const setW = (f: Flow, k: 'w1' | 'w2' | 'w3', v: number) => setDraft({ ...draft, weights: { ...draft.weights, [f]: { ...draft.weights[f], [k]: v } } });

  function save() {
    const diffs: string[] = [];
    FLOWS.forEach(({ f }) => {
      const a = state.config.weights[f], b = draft.weights[f];
      if (a.w1 !== b.w1 || a.w2 !== b.w2 || a.w3 !== b.w3) diffs.push(`Flow ${f} weights ${a.w1}/${a.w2}/${a.w3} → ${b.w1}/${b.w2}/${b.w3}`);
    });
    if (draft.batchWindowMs !== state.config.batchWindowMs) diffs.push(`Batch window ${state.config.batchWindowMs} → ${draft.batchWindowMs} ms`);
    if (draft.minRiskScore !== state.config.minRiskScore) diffs.push(`Min risk score ${state.config.minRiskScore} → ${draft.minRiskScore}`);
    if (draft.vickrey !== state.config.vickrey) diffs.push(`Vickrey second-price ${draft.vickrey ? 'on' : 'off'}`);
    setConfig(draft, diffs.join(' · ') || 'No-op save');
  }

  return (
    <>
      <PageHeader eyebrow="Exchange · SBBA configuration" title="Auction config"
        desc="Flow-specific weights for the SBBA auction, plus the failover and collateral rules. Changes apply from the next batch and are written to the audit log."
        actions={<>
          <button className="btn-ghost" onClick={() => setDraft(DEFAULT_CONFIG)}><RotateCcw size={14} /> Defaults</button>
          <button className="btn-primary" disabled={!dirty || !valid} onClick={save}><Save size={15} /> Save configuration</button>
        </>} />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-5">
          <Card title="Scoring weights" sub="Score = w1 × U + w2 × R − w3 × L/Lmax, where U = (d·P − Y) / (d·P) rewards a lower yield ask. Each flow's weights must add up to 1.">
            <div className="grid gap-4 md:grid-cols-3">
              {FLOWS.map(({ f, name }, i) => (
                <div key={f} className="rounded-xl border border-ink-200 p-4">
                  <div className="flex items-center justify-between mb-3"><FlowTag flow={f} /><span className={cx('text-xs tabular', Math.abs(sums[i] - 1) < 0.001 ? 'text-exchange-600' : 'text-red-700')}>Σ {sums[i].toFixed(2)}</span></div>
                  <p className="text-xs text-ink-500 mb-3">{name}</p>
                  {(['w1', 'w2', 'w3'] as const).map((k) => (
                    <div key={k} className="mb-2.5">
                      <label htmlFor={`${f}-${k}`} className="flex justify-between text-sm"><span>{k === 'w1' ? 'Buyer surplus (U)' : k === 'w2' ? 'Reliability' : 'Latency'} <span className="font-mono text-xs text-ink-400">{k}</span></span><span className="tabular font-medium">{draft.weights[f][k].toFixed(2)}</span></label>
                      <input id={`${f}-${k}`} type="range" min={0} max={1} step={0.05} value={draft.weights[f][k]} onChange={(e) => setW(f, k, Number(e.target.value))} className="w-full accent-exchange-600" />
                    </div>
                  ))}
                </div>
              ))}
            </div>
            {!valid && <div className="mt-3"><Callout tone="warn">Each flow's weights must add up to 1.00 before you can save.</Callout></div>}
          </Card>

          <Card title="Batch and risk parameters">
            <div className="grid gap-4 sm:grid-cols-2">
              <div><label htmlFor="bw" className="text-sm font-medium">Batch window (ms)</label><input id="bw" className="input mt-1.5 tabular" value={draft.batchWindowMs} onChange={(e) => setDraft({ ...draft, batchWindowMs: Number(e.target.value.replace(/\D/g, '')) || 0 })} /><p className="text-xs text-ink-500 mt-1">Default 50 ms; Lmax {draft.latencyMaxMs} ms in the score.</p></div>
              <div><label htmlFor="rs" className="text-sm font-medium">Minimum buyer risk score</label><input id="rs" className="input mt-1.5 tabular" value={draft.minRiskScore} onChange={(e) => setDraft({ ...draft, minRiskScore: Number(e.target.value.replace(/\D/g, '')) || 0 })} /><p className="text-xs text-ink-500 mt-1">L3 VaR / velocity / fraud gate before broadcast.</p></div>
              <div><p className="text-sm font-medium">Failover and slashing</p><p className="mt-1.5 text-sm text-ink-700">Ranks 2–3 are hot backups. A declining buying partner is slashed {draft.slashPct51}% of A on ISO 51 ({draft.slashPctOther}% otherwise) into the Guarantee Fund. Deposit α = {draft.depositPct}% of the mandate limit; Passkey re-check every {draft.vppMaxDays} days (DiscountAI policy).</p></div>
              <label className="flex items-start gap-3 rounded-lg border border-ink-200 p-3 cursor-pointer">
                <input type="checkbox" className="mt-1 accent-exchange-600" checked={draft.vickrey} onChange={(e) => setDraft({ ...draft, vickrey: e.target.checked })} />
                <span><span className="text-sm font-medium">Vickrey second-price</span><span className="block text-xs text-ink-500">Winner is paid at the runner-up bid, which rewards truthful bidding.</span></span>
              </label>
            </div>
          </Card>
        </div>

        <div className="flex flex-col gap-5">
          <Card title="Impact preview" sub={`Reference card bidders for ${SCENARIO.product} (A = ${inr(SPLIT.A)}), live vs draft weights`}>
            <table className="w-full text-sm">
              <thead><tr><th className="th">Rank</th><th className="th">Node</th><th className="th text-right">Live</th><th className="th text-right">Draft</th></tr></thead>
              <tbody>
                {preview.next.map((b, i) => {
                  const live = preview.now.find((x) => x.id === b.id);
                  return (
                    <tr key={b.id} className={i === 0 ? 'bg-exchange-50' : ''}>
                      <td className="td tabular">{i + 1}{i === 0 && <Badge tone="green">winner</Badge>}</td>
                      <td className="td">{b.nodeId}<span className="block text-xs text-ink-500">asks {inr(b.yieldAsk)} · {Math.round(b.reliability * 100)}% reliable · {b.latencyMs} ms</span></td>
                      <td className="td text-right tabular text-ink-500">{live?.score?.toFixed(3)}</td>
                      <td className="td text-right tabular font-semibold">{b.score?.toFixed(3)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {preview.now[0]?.id !== preview.next[0]?.id && <div className="mt-3"><Callout tone="warn" title="Winner changes">These weights would move the reference win from {preview.now[0]?.nodeId} to {preview.next[0]?.nodeId}.</Callout></div>}
          </Card>
          <Card title="Audit log" sub="Every saved change, newest first">
            <ul className="flex flex-col gap-3 text-sm">
              {state.configAudit.map((e, i) => (
                <li key={i}><p className="text-ink-800">{e.change}</p><p className="text-xs text-ink-500">{e.who} · {dateTime(e.at)}</p></li>
              ))}
            </ul>
          </Card>
        </div>
      </div>
    </>
  );
}
