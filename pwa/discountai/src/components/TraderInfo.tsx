import { Building2, Headset } from 'lucide-react';
import { TRADER, hasSupport, traderName } from '../legal';

/** Who the trader is and how to reach a person. Fields left empty in src/legal.ts are not shown. */
export function TraderInfo({ compact = false }: { compact?: boolean }) {
  return (
    <span className={compact ? 'flex flex-col gap-0.5' : 'inline-flex flex-wrap items-center gap-x-4 gap-y-1'}>
      <span className="inline-flex items-center gap-1.5">
        <Building2 size={13} /> Trader: {traderName()}{TRADER.address ? ` · ${TRADER.address}` : ''}
      </span>
      {hasSupport() && (
        <span className="inline-flex items-center gap-1.5">
          <Headset size={13} /> Talk to a person:{' '}
          {TRADER.supportEmail && <a className="underline" href={`mailto:${TRADER.supportEmail}`}>{TRADER.supportEmail}</a>}
          {TRADER.supportEmail && TRADER.supportPhone ? ' · ' : ''}
          {TRADER.supportPhone && <a className="underline" href={`tel:${TRADER.supportPhone.replace(/\s/g, '')}`}>{TRADER.supportPhone}</a>}
        </span>
      )}
    </span>
  );
}
