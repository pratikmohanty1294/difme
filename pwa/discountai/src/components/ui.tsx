import type { ReactNode } from 'react';
import type { RequestStatus } from '../sim/types';

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(' ');
}

export function PageHeader({ eyebrow, title, desc, actions }: { eyebrow: string; title: string; desc?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4 mb-6">
      <div className="min-w-0">
        <p className="label mb-1.5">{eyebrow}</p>
        <h1 className="text-[26px] font-semibold tracking-tight text-ink-950">{title}</h1>
        {desc && <p className="mt-1.5 text-ink-600 max-w-3xl">{desc}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ title, sub, right, children, className, pad = true }: { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children: ReactNode; className?: string; pad?: boolean }) {
  return (
    <section className={cx('card min-w-0', className)}>
      {(title || right) && (
        <header className="flex items-start justify-between gap-3 px-5 pt-4 pb-3 border-b border-ink-100">
          <div className="min-w-0">
            {title && <h2 className="text-[15px] font-semibold text-ink-900">{title}</h2>}
            {sub && <p className="text-xs text-ink-500 mt-0.5">{sub}</p>}
          </div>
          {right}
        </header>
      )}
      <div className={pad ? 'p-5' : ''}>{children}</div>
    </section>
  );
}

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'good' | 'warn' | 'neutral' }) {
  return (
    <div className="card px-5 py-4 min-w-0">
      <p className="label">{label}</p>
      <p className="mt-1.5 text-[26px] leading-tight font-semibold font-display tabular text-ink-950">{value}</p>
      {sub && <p className={cx('mt-1 text-xs', tone === 'good' ? 'text-exchange-600' : tone === 'warn' ? 'text-amber-700' : 'text-ink-500')}>{sub}</p>}
    </div>
  );
}

const toneMap = {
  green: 'bg-exchange-50 text-exchange-600 ring-exchange-100',
  amber: 'bg-amber-50 text-amber-800 ring-amber-200',
  red: 'bg-red-50 text-red-700 ring-red-200',
  blue: 'bg-investor-50 text-investor-600 ring-investor-100',
  gray: 'bg-ink-100 text-ink-600 ring-ink-200',
  brand: 'bg-brand-50 text-brand-700 ring-brand-100',
  purple: 'bg-audit-50 text-audit-600 ring-audit-100',
  orange: 'bg-buyer-50 text-buyer-600 ring-buyer-100',
} as const;
export type Tone = keyof typeof toneMap;

export function Badge({ tone = 'gray', children, dot }: { tone?: Tone; children: ReactNode; dot?: boolean }) {
  return (
    <span className={cx('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11.5px] font-medium ring-1 ring-inset', toneMap[tone])}>
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current live-dot" />}
      {children}
    </span>
  );
}

export const statusTone: Record<RequestStatus, Tone> = {
  Pending: 'amber', Matched: 'blue', 'Awaiting confirmation': 'amber', Confirmed: 'blue', Success: 'green', 'Timed Out': 'gray', Cancelled: 'gray', Refunded: 'purple',
};
const STATUS_LABEL: Record<RequestStatus, string> = {
  Pending: 'Pending', Matched: 'Matched (provisional)', 'Awaiting confirmation': 'Waiting for your confirmation', Confirmed: 'Confirmed', Success: 'Shipped · settled', 'Timed Out': 'Timed out', Cancelled: 'Cancelled', Refunded: 'Refunded',
};

export function StatusBadge({ status }: { status: RequestStatus }) {
  return <Badge tone={statusTone[status]} dot={status === 'Pending' || status === 'Matched' || status === 'Confirmed'}>{STATUS_LABEL[status]}</Badge>;
}

export function KV({ rows, mono }: { rows: [ReactNode, ReactNode][]; mono?: boolean }) {
  return (
    // Labels take their natural width but never more than 45%, so a long label wraps instead of squeezing the values.
    <dl className="grid grid-cols-[fit-content(45%)_minmax(0,1fr)] gap-x-6 gap-y-2 text-sm">
      {rows.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-ink-500">{k}</dt>
          <dd className={cx('text-ink-900 text-right tabular break-words', mono && 'font-mono text-[12.5px]')}>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function JsonView({ value, className }: { value: unknown; className?: string }) {
  return (
    <pre className={cx('rounded-lg bg-ink-950 text-[#cfe3e4] text-[12px] leading-relaxed font-mono p-4 overflow-x-auto', className)}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-ink-200 px-5 py-8 text-center">
      <p className="font-medium text-ink-700">{title}</p>
      {children && <div className="mt-1 text-sm text-ink-500">{children}</div>}
    </div>
  );
}

export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex flex-wrap gap-2">
      {steps.map((s, i) => (
        <li key={s} className={cx('flex items-center gap-2 rounded-full pl-1 pr-3 py-1 text-sm ring-1 ring-inset',
          i < current ? 'bg-brand-50 text-brand-700 ring-brand-100' : i === current ? 'bg-ink-900 text-white ring-ink-900' : 'bg-white text-ink-500 ring-ink-200')}>
          <span className={cx('grid h-5 w-5 place-items-center rounded-full text-[11px] font-semibold',
            i < current ? 'bg-brand-600 text-white' : i === current ? 'bg-white text-ink-900' : 'bg-ink-100 text-ink-500')}>{i < current ? '✓' : i + 1}</span>
          {s}
        </li>
      ))}
    </ol>
  );
}

export function Segmented<T extends string>({ value, options, onChange, size = 'md' }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; size?: 'sm' | 'md' }) {
  return (
    <div className="inline-flex rounded-lg bg-ink-100 p-0.5" role="radiogroup">
      {options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={value === o.value} onClick={() => onChange(o.value)}
          className={cx('rounded-md font-medium transition-colors', size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-sm',
            value === o.value ? 'bg-white text-ink-900 shadow-sm' : 'text-ink-500 hover:text-ink-800')}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Meter({ value, tone = 'brand' }: { value: number; tone?: 'brand' | 'blue' | 'green' | 'amber' }) {
  const c = { brand: 'bg-brand-500', blue: 'bg-investor-600', green: 'bg-exchange-600', amber: 'bg-amber-500' }[tone];
  return (
    <div className="h-1.5 w-full rounded-full bg-ink-100 overflow-hidden">
      <div className={cx('h-full rounded-full transition-all duration-500', c)} style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
    </div>
  );
}

export function FlowTag({ flow }: { flow: 'A' | 'B' | 'C' }) {
  const t = { A: ['blue', 'Flow A · Card'], B: ['purple', 'Flow B · USDC / RWA'], C: ['orange', 'Flow C · UPI'] } as const;
  return <Badge tone={t[flow][0]}>{t[flow][1]}</Badge>;
}

export function Callout({ tone = 'info', title, children }: { tone?: 'info' | 'warn' | 'good'; title?: string; children: ReactNode }) {
  const cls = { info: 'bg-brand-50 border-brand-100 text-brand-700', warn: 'bg-amber-50 border-amber-200 text-amber-900', good: 'bg-exchange-50 border-exchange-100 text-exchange-600' }[tone];
  return (
    <div className={cx('rounded-lg border px-4 py-3 text-sm', cls)}>
      {title && <p className="font-semibold mb-0.5">{title}</p>}
      <div className="text-ink-700">{children}</div>
    </div>
  );
}
