import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { VIC_MODE_LABEL, type VicResult } from '../sim/vic';
import { Badge, JsonView, cx } from './ui';

/** One card-network agentic commerce call: endpoint, live/simulated badge, and the exact the card network request and response. */
export function VicCallView({ r, title }: { r: VicResult; title: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={cx('rounded-lg border p-3 text-[13px]', r.ok ? 'border-investor-100 bg-investor-50/40' : 'border-red-200 bg-red-50/60')}>
      <button className="flex w-full flex-wrap items-center gap-2 text-left" onClick={() => setOpen(!open)} aria-expanded={open}>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span className="font-medium text-ink-900">{title}</span>
        <span className="font-mono text-[11px] text-ink-500">{r.method} {r.endpoint}</span>
        <Badge tone={r.mode === 'live' ? 'green' : 'blue'}>{VIC_MODE_LABEL[r.mode]}</Badge>
        {!r.ok && <Badge tone="red">Failed</Badge>}
        <span className="text-xs text-ink-500 tabular">{r.ms} ms</span>
      </button>
      {r.error && <p className="mt-1 text-xs text-ink-500">{r.error}</p>}
      {open && (
        <div className="mt-2 grid gap-2 md:grid-cols-2">
          {r.request !== undefined && <div><p className="label mb-1">Request to the card network</p><JsonView value={r.request} /></div>}
          <div><p className="label mb-1">Response</p><JsonView value={r.response ?? {}} /></div>
        </div>
      )}
    </div>
  );
}
