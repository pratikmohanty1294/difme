import { useState, type ReactNode } from 'react';
import { Pause, Play, Plus, Ban } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { dateTime, inr, inr2 } from '../../sim/format';
import type { Mandate } from '../../sim/types';
import { Badge, Callout, Card, FlowTag, KV, Meter, PageHeader, cx } from '../../components/ui';
import { href } from '../../router';
import { vic, type VicResult } from '../../sim/vic';
import { VicCallView } from '../../components/VicCallView';

export default function Mandates({ focus }: { focus?: string }) {
  const { state, patchMandate } = useEngine();
  const [sel, setSel] = useState(focus ?? state.mandates[0]?.id);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [cancelCall, setCancelCall] = useState<VicResult>();
  const [termAck, setTermAck] = useState<{ id: string; at: Date }>();
  const m = state.mandates.find((x) => x.id === sel) ?? state.mandates[0];

  return (
    <>
      <PageHeader eyebrow="Buying partner · smart contract mandates" title="Mandates"
        desc="Each mandate is an on-chain contract that sets the budget, merchants, goods and validity your agent buys within, with no human in the loop; items ship to the matched buyer. Pause stops new bids; “Terminate the contract here” ends it and cancels the the card network instruction."
        actions={<a className="btn-primary" href={href('investor/factory')}><Plus size={15} /> New mandate</a>} />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
        <div className="flex flex-col gap-2.5">
          {state.mandates.map((x) => (
            <button key={x.id} onClick={() => { setSel(x.id); setConfirmRevoke(false); }}
              className={cx('card text-left px-5 py-4 transition-colors', x.id === m.id ? 'ring-2 ring-investor-600 border-transparent' : 'hover:bg-ink-50')}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium text-ink-900">{x.name}</span>
                <span className="font-mono text-[11px] text-ink-500">{x.id}</span>
                <span className="ml-auto"><StatusTag m={x} /></span>
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-3 text-sm text-ink-600">
                <FlowTag flow={x.flow} /><span>{x.instrument}</span>
              </div>
              <div className="mt-3 grid grid-cols-[1fr_auto] items-center gap-3">
                <Meter value={(x.utilised / x.limit) * 100} tone="blue" />
                <span className="text-xs tabular text-ink-600">{inr(x.utilised)} / {inr(x.limit)}</span>
              </div>
            </button>
          ))}
        </div>

        {m && (
          <div className="flex flex-col gap-5 min-w-0">
            <Card title={<span className="flex items-center gap-2">{m.name} <span className="font-mono text-xs text-ink-500">{m.id}</span></span>} sub={`Contract ${m.contract} · version ${m.version}`} right={<StatusTag m={m} />}>
              <div className="grid grid-cols-3 gap-3 mb-5">
                <div><p className="label">Yield earned</p><p className="text-xl font-semibold tabular">{inr2(m.yieldEarned)}</p></div>
                <div><p className="label">Utilisation</p><p className="text-xl font-semibold tabular">{Math.round((m.utilised / m.limit) * 100)}%</p></div>
                <div><p className="label">Created</p><p className="text-xl font-semibold">{dateTime(m.createdAt).split(',')[0]}</p></div>
              </div>
              <KV rows={[
                ['Asset type', m.assetType],
                ['Instrument', m.instrument],
                ...(m.bin ? [['Card BIN', <span key="b" className="font-mono">{m.bin}</span>] as [string, ReactNode]] : []),
                ...(m.intentType ? [
                  ['Instruction', m.intentType],
                  ['budget_total', m.budgetTotal ? `${inr(m.budgetTotal)} · ${inr(Math.max(0, m.budgetTotal - m.utilised))} left` : '—'],
                  ['approved_merchants', m.merchants?.join(', ') ?? '—'],
                  ['goods', m.goods?.join(', ') ?? '—'],
                  ['visa_instruction', m.vicInstructionId ?? 'not registered'],
                  ['valid_until', m.vppRefreshDue ? new Date(m.vppRefreshDue).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—'],
                ] as [string, string][] : []),
                ['Security deposit', m.deposit == null ? '—' : m.status === 'Revoked'
                  ? `${inr(m.depositReturned ?? m.deposit)} · returned to you`
                  : `${inr(m.deposit)} · held in escrow, refundable when the mandate ends`],
                ['max_per_tx', inr(m.maxPerTx)],
                ['min_yield', `${m.minYieldPct.toFixed(1)}%`],
                ['MCC rule', m.mccRule === 'exclusion' ? `Blocks ${m.mccs.join(', ')}` : `Allows only ${m.mccs.join(', ')}`],
                ['Geography', m.regions],
                ['Frequency', m.frequency],
                ['Balance check', m.flow === 'A' ? 'Bank API every 5 min' : m.flow === 'B' ? 'Real-time token balance' : 'NPCI block balance API'],
              ]} />
              <div className="mt-5 flex flex-wrap gap-2 border-t border-ink-100 pt-4">
                {m.status === 'LIVE' && <button className="btn-secondary" onClick={() => patchMandate(m.id, { status: 'Paused' })}><Pause size={14} /> Pause bidding</button>}
                {m.status === 'Paused' && <button className="btn-secondary" onClick={() => patchMandate(m.id, { status: 'LIVE' })}><Play size={14} /> Resume</button>}
                {m.status !== 'Revoked' && !confirmRevoke && <button className="btn-ghost text-red-700 hover:bg-red-50" onClick={() => setConfirmRevoke(true)}><Ban size={14} /> Terminate the contract here</button>}
              </div>
              {confirmRevoke && m.status !== 'Revoked' && (
                <div className="mt-3">
                  <Callout tone="warn" title={`Terminate contract ${m.id}?`}>
                    The contract stops accepting matches now. Matches already in flight finish under version {m.version}{m.deposit ? `, then your ${inr(m.deposit)} security deposit is returned` : ''}. You can't undo this; create a new mandate instead.
                    <div className="mt-3 flex gap-2">
                      <button className="btn bg-red-600 text-white hover:bg-red-700" onClick={async () => {
                        patchMandate(m.id, { status: 'Revoked', depositReturned: m.deposit ?? 0 }); setConfirmRevoke(false); setTermAck({ id: m.id, at: new Date() });
                        if (m.vicInstructionId) setCancelCall(await vic('instruction.cancel', { instructionId: m.vicInstructionId }));
                      }}>Confirm the contract termination</button>
                      <button className="btn-secondary" onClick={() => setConfirmRevoke(false)}>Keep it</button>
                    </div>
                  </Callout>
                </div>
              )}
              {termAck?.id === m.id && (
                <div className="mt-3">
                  <Callout tone="good" title="Termination received">
                    We received your termination of contract <span className="font-mono">{m.id}</span> (version {m.version}) on {termAck.at.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}. It takes effect immediately: your agent places no new bids, and matches already in flight finish. {m.deposit ? <>Your security deposit of {inr(m.depositReturned ?? m.deposit)} is released from escrow back to you once those matches settle. </> : null}In the live service this acknowledgement is also e-mailed to you.
                  </Callout>
                </div>
              )}
              {cancelCall && m.status === 'Revoked' && <div className="mt-3"><VicCallView title="Cancel the card network purchase instruction" r={cancelCall} /></div>}
            </Card>
            <Card title="Version history" sub="Every parameter edit creates a new contract version">
              <ol className="flex flex-col gap-2.5 text-sm">
                {Array.from({ length: m.version }, (_, i) => m.version - i).map((v) => (
                  <li key={v} className="flex items-baseline gap-3">
                    <Badge tone={v === m.version ? 'blue' : 'gray'}>v{v}</Badge>
                    <span className="text-ink-700">{v === 1 ? 'Deployed from the smart mandate factory' : v === 2 ? 'Yield floor raised to ' + m.minYieldPct.toFixed(1) + '%' : 'Max per transaction set to ' + inr(m.maxPerTx)}</span>
                  </li>
                ))}
              </ol>
            </Card>
          </div>
        )}
      </div>
    </>
  );
}

function StatusTag({ m }: { m: Mandate }) {
  return <Badge tone={m.status === 'LIVE' ? 'green' : m.status === 'Paused' ? 'amber' : 'red'} dot={m.status === 'LIVE'}>{m.status}</Badge>;
}
