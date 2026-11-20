import { useState } from 'react';
import { ChevronDown, Headphones, Laptop, Monitor, Sparkles } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { dateTime, inr } from '../../sim/format';
import type { BuyRequest, RequestStatus } from '../../sim/types';
import { Card, KV, PageHeader, Segmented, StatusBadge, cx } from '../../components/ui';
import { RequestTracker } from './RequestTracker';
import { href } from '../../router';

const ICON = { laptop: Laptop, headphones: Headphones, sparkles: Sparkles, monitor: Monitor };

export default function MyRequests({ focus }: { focus?: string }) {
  const { state } = useEngine();
  const [filter, setFilter] = useState<'all' | 'active' | 'done' | 'failed'>('all');
  const [open, setOpen] = useState<string | undefined>(focus ?? state.requests[0]?.id);

  const match = (s: RequestStatus) =>
    filter === 'all' || (filter === 'active' && (s === 'Pending' || s === 'Matched' || s === 'Confirmed')) || (filter === 'done' && s === 'Success') || (filter === 'failed' && (s === 'Refunded' || s === 'Timed Out'));
  const list = state.requests.filter((r) => match(r.status));
  const saved = state.requests.reduce((a, r) => a + (r.status === 'Success' && r.split ? r.split.buyerSaves : 0), 0);

  return (
    <>
      <PageHeader eyebrow="Buyer · status tracking" title="My requests"
        desc={<>Every marketplace request with its status, settlement breakdown and proof chain. You have saved <b className="text-ink-900">{inr(saved)}</b> through the exchange.</>}
        actions={<a className="btn-primary" href={href('buyer')}>New request</a>} />
      <div className="mb-4">
        <Segmented value={filter} onChange={setFilter} options={[{ value: 'all', label: 'All' }, { value: 'active', label: 'In flight' }, { value: 'done', label: 'Successful' }, { value: 'failed', label: 'Refunded or expired' }]} />
      </div>
      <div className="flex flex-col gap-3">
        {list.length === 0 && <Card><p className="text-ink-500">No requests in this view.</p></Card>}
        {list.map((r) => <Row key={r.id} r={r} open={open === r.id} onToggle={() => setOpen(open === r.id ? undefined : r.id)} />)}
      </div>
    </>
  );
}

function Row({ r, open, onToggle }: { r: BuyRequest; open: boolean; onToggle: () => void }) {
  const Icon = ICON[r.icon];
  return (
    <section className="card overflow-hidden">
      <button className="w-full flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4 text-left hover:bg-ink-50" onClick={onToggle} aria-expanded={open}>
        <span className="grid h-10 w-10 place-items-center rounded-lg bg-buyer-50 text-buyer-600"><Icon size={18} /></span>
        <span className="min-w-0 flex-1">
          <span className="block font-medium text-ink-900">{r.product}</span>
          <span className="block text-xs text-ink-500">{r.merchant} · <span className="font-mono">{r.id}</span> · {dateTime(r.createdAt)}</span>
        </span>
        <span className="text-right tabular">
          <span className="block font-semibold">{r.split ? inr(r.split.B) : inr(r.list)}</span>
          <span className="block text-xs text-ink-500">{r.status === 'Success' && r.split ? `saved ${inr(r.split.buyerSaves)}` : `list ${inr(r.list)}`}</span>
        </span>
        <StatusBadge status={r.status} />
        <ChevronDown size={16} className={cx('text-ink-400 transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="border-t border-ink-100 bg-ink-50/40 px-5 py-5 grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
          <RequestTracker req={r} />
          <div className="rounded-lg border border-ink-200 bg-white p-4 h-fit">
            <p className="label mb-2">Request details</p>
            <KV rows={[
              ['Instrument', r.instrument],
              ['BIN', <span key="b" className="font-mono">{r.bin}</span>],
              ['List price P', inr(r.list)],
              ['Your max price', inr(r.maxPrice)],
              ['Quote B', r.quoteB ? inr(r.quoteB) : '—'],
              ['Card hold', r.buyerHoldId ? <span key="h" className="font-mono">{r.buyerHoldId}</span> : 'none (no match)'],
              ['Wait time', r.expiry],
              ['MCC', r.mcc],
              ['Auction', <span key="a" className="font-mono">{r.auctionId}</span>],
              ['L3 risk score', r.riskScore],
            ]} />
          </div>
        </div>
      )}
    </section>
  );
}
