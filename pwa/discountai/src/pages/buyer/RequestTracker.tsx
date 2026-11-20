import { useEffect, useState } from 'react';
import { Activity, RotateCcw, ShieldCheck } from 'lucide-react';
import { href } from '../../router';
import { refundFor, useEngine } from '../../sim/engine';
import { inr, ms } from '../../sim/format';
import type { BuyRequest, RefundCase, TimelineStep } from '../../sim/types';
import { Badge, Callout, KV, cx } from '../../components/ui';

const PHASES: { key: TimelineStep['phase']; label: string }[] = [
  { key: 'POI', label: 'Request' }, { key: 'POL', label: 'Matched' }, { key: 'PRL', label: 'Hold placed' }, { key: 'POE', label: 'Bought · shipped' }, { key: 'POC', label: 'Settled' },
];
const CASES: { id: RefundCase; label: string; note: string }[] = [
  { id: 'pre-payout', label: 'Return before payout', note: 'Case 1 · buying partner payout frozen' },
  { id: 'post-payout', label: 'Return after payout', note: 'Case 2 · A + Y clawed back' },
  { id: 'partial', label: 'Return half', note: 'Case 3 · proportional' },
];

/** The quote waiting for the buyer's OK: price, what DiscountAI is paid, and a countdown that fails closed. */
function ConfirmPanel({ req, onAnswer }: { req: BuyRequest; onAnswer: (yes: boolean) => void }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const left = Math.max(0, Math.round((Date.parse(req.confirmDeadline ?? '') - now) / 1000));
  const s = req.split!;
  return (
    <div className="rounded-xl border border-amber-300 bg-amber-50/70 p-4">
      <p className="font-medium text-ink-900">Confirm your order: {inr(s.B)} for {req.product}</p>
      <p className="mt-1 text-[13px] text-ink-700">{req.confirmReason}. A buying partner’s agent buys it at {req.merchant} in their own name and ships it to you. DiscountAI is paid a {inr(s.F)} platform fee and the buying partner a {inr(s.Y)} yield, both inside this price. Nothing is held on your card until you confirm.</p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button className="btn-primary" onClick={() => onAnswer(true)}>Confirm order · {inr(s.B)}</button>
        <button className="btn-secondary" onClick={() => onAnswer(false)}>Decline</button>
        <span className="text-xs text-amber-900 tabular">{left > 0 ? `Cancels automatically in ${left} s` : 'Cancelling…'}</span>
      </div>
    </div>
  );
}

export function RequestTracker({ req, compact }: { req: BuyRequest; compact?: boolean }) {
  const { refundRequest, confirmQuote } = useEngine();
  const [confirm, setConfirm] = useState<RefundCase>();
  const [ack, setAck] = useState<{ at: Date; label: string; amount: number }>();
  const reached = new Set(req.timeline.map((s) => s.phase));
  const failed = req.status === 'Timed Out';
  const settled = req.status === 'Success' || req.status === 'Refunded';
  const lastIdx = PHASES.reduce((acc, p, i) => (reached.has(p.key) ? i : acc), -1);
  const s = req.split;

  return (
    <div className="flex flex-col gap-5">
      {/* phase rail */}
      <ol className="grid grid-cols-5 gap-1.5" aria-label="Request progress">
        {PHASES.map((p, i) => {
          const done = reached.has(p.key) && (i < lastIdx || settled);
          const current = i === lastIdx && !done;
          const broke = failed && i === lastIdx + 1;
          return (
            <li key={p.key} className="min-w-0">
              <div className={cx('h-1.5 rounded-full', done ? 'bg-exchange-600' : current ? 'bg-amber-400 live-dot' : broke ? 'bg-red-400' : 'bg-ink-100')} />
              <p className={cx('mt-1.5 text-[11.5px] truncate', done || current ? 'text-ink-800 font-medium' : broke ? 'text-red-700' : 'text-ink-400')}>
                <span className="font-mono text-[10.5px] mr-1">{p.key}</span>{p.label}
              </p>
            </li>
          );
        })}
      </ol>

      {(failed || req.status === 'Refunded') && req.failReason && <Callout tone={failed ? 'warn' : 'info'} title={failed ? 'Timed out' : 'Refunded'}>{req.failReason}</Callout>}
      {req.status === 'Awaiting confirmation' && s && <ConfirmPanel req={req} onAnswer={(yes) => confirmQuote(req.id, yes)} />}
      {req.status === 'Matched' && <Callout tone="info" title="Matched, not yet confirmed">The buying partner's agent is checking out at {req.merchant}. You'll get "Order confirmed" only once the merchant approves the charge.</Callout>}

      {settled && s && (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="rounded-lg bg-exchange-50 p-4">
            <p className="label text-exchange-600">You paid</p>
            <p className="text-3xl font-semibold font-display tabular text-ink-950">{inr(s.B)}</p>
            <p className="text-sm text-exchange-600 mt-0.5">Saved {inr(s.buyerSaves)} on the {inr(s.list)} list price, without a Bank X card</p>
            <p className="text-xs text-ink-600 mt-3">Order <span className="font-mono">{req.orderId}</span>{req.trackingId && <> · tracking <span className="font-mono">{req.trackingId}</span></>} · your {inr(s.B)} hold was captured when it shipped</p>
          </div>
          <div className="rounded-lg border border-ink-200 p-4">
            <p className="label mb-2">Where the money went</p>
            <KV rows={[
              [`Bank X discount (${s.discountPct}%)`, `−${inr(s.discount)}`],
              ['Buying partner\'s card charged at the merchant (A)', inr(s.A)],
              ['Buying partner yield (Y)', inr(s.Y)],
              ['DiscountAI fee (F)', inr(s.F)],
              ['You pay B = A + Y + F', inr(s.B)],
              ...(req.payoutPlan ? [[
                'Buying partner payout (risk-tiered)',
                req.payoutPlan.tier === 1
                  ? `${inr(req.payoutPlan.principal)} paid to their card bill now; ${inr(req.payoutPlan.yield)} yield released ${new Date(req.payoutPlan.releaseAt).toLocaleDateString('en-IN')} after the return window`
                  : `${inr(req.payoutPlan.principal + req.payoutPlan.yield)} held until ${new Date(req.payoutPlan.releaseAt).toLocaleDateString('en-IN')} (new buying partner), then paid to their card bill`,
              ] as [string, string]] : []),
            ]} />
          </div>
        </div>
      )}

      {req.status === 'Success' && s && !compact && (
        <div className="rounded-lg border border-ink-200 p-4">
          <p className="label mb-2 flex items-center gap-1.5"><RotateCcw size={13} /> Withdraw from contract here</p>
          <p className="text-[13px] text-ink-600 mb-3">Choose what you are sending back, then confirm. Refunds run in two legs: the merchant refunds the buying partner's card, and DiscountAI refunds you straight away from escrow.</p>
          <div className="flex flex-wrap gap-2">
            {CASES.map((c) => {
              const r = refundFor(s, c.id);
              return confirm === c.id ? (
                <span key={c.id} className="inline-flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-[13px] text-amber-900">
                  Withdraw and get {inr(r.buyerRefund)} back?
                  <button className="btn-primary px-2.5 py-1 text-xs" onClick={() => { refundRequest(req.id, c.id); setConfirm(undefined); setAck({ at: new Date(), label: c.label, amount: r.buyerRefund }); }}>Confirm withdrawal</button>
                  <button className="btn-ghost px-2 py-1 text-xs" onClick={() => setConfirm(undefined)}>Cancel</button>
                </span>
              ) : (
                <button key={c.id} className="btn-secondary text-left" onClick={() => setConfirm(c.id)}>
                  <span>{c.label}<span className="block text-[11px] text-ink-500 font-normal">{c.note} · you get {inr(r.buyerRefund)}</span></span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      {ack && (
        <Callout tone="good" title="Withdrawal received">
          We received your withdrawal for order <span className="font-mono">{req.orderId}</span> ({ack.label.toLowerCase()}) on {ack.at.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}. Your refund of {inr(ack.amount)} is on its way. In the live service this acknowledgement is also e-mailed to you.
        </Callout>
      )}

      {(req.refunds?.length ?? 0) > 0 && (
        <div className="rounded-lg border border-ink-200 p-4">
          <p className="label mb-2">Refunds (two legs)</p>
          {req.refunds!.map((r, i) => (
            <KV key={i} rows={[
              ['Case', r.case === 'pre-payout' ? '1 · before buying partner payout' : r.case === 'post-payout' ? '2 · after buying partner payout' : '3 · partial return'],
              ['Leg 1 · merchant → buying partner\'s card', inr(r.merchantRefundToInvestor)],
              ['Leg 2 · DiscountAI → your payment method', inr(r.buyerRefund)],
              ['Recovered from buying partner', r.investorClawback ? inr(r.investorClawback) : '—'],
              ['Fee reversed', inr(r.feeReversal)],
              ['How', r.recovery],
            ]} />
          ))}
        </div>
      )}

      {!compact && (
        <div>
          <p className="label mb-2">Event log</p>
          <ol className="relative border-l border-ink-200 ml-1.5 flex flex-col gap-3">
            {req.timeline.map((st, i) => (
              <li key={i} className="rise pl-4 relative">
                <span className={cx('absolute -left-[5px] top-1.5 h-2.5 w-2.5 rounded-full ring-2 ring-white', st.phase === 'FAIL' ? 'bg-red-500' : st.phase === 'REFUND' ? 'bg-audit-600' : 'bg-brand-500')} />
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-mono text-[11px] text-ink-400 w-16 tabular">t+{ms(st.t)}</span>
                  <Badge tone="gray">{st.actor}</Badge>
                  <span className="text-[13.5px] text-ink-900">{st.label}</span>
                </div>
                {st.detail && <p className="ml-[72px] text-xs text-ink-500 mt-0.5">{st.detail}</p>}
              </li>
            ))}
            {(req.status === 'Pending' || req.status === 'Matched' || req.status === 'Confirmed') && <li className="pl-4 text-xs text-ink-500 live-dot">Waiting for next event…</li>}
          </ol>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <a className="btn-secondary" href={href('exchange', req.id)}><Activity size={15} /> Watch the auction</a>
        <a className={cx('btn-secondary', req.proofs.length === 0 && 'pointer-events-none opacity-50')} href={href('audit', req.id)}><ShieldCheck size={15} /> Proof chain ({req.proofs.length}/5)</a>
      </div>
    </div>
  );
}
