import { useState } from 'react';
import { BellRing, Settings2 } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { inr } from '../../sim/format';
import type { BuyerPrefs } from '../../sim/types';
import { Callout, cx } from '../../components/ui';

const MODES: { id: BuyerPrefs['mode']; title: string; note: string }[] = [
  { id: 'always', title: 'Confirm every order', note: 'ShopBot shows you each quote and waits for your OK before anything is held on your card.' },
  { id: 'criteria', title: 'Confirm only some orders', note: 'Orders within your price limit and dates go ahead on their own; the rest wait for your OK.' },
  { id: 'never', title: 'No confirmation needed', note: 'Orders inside your request go ahead without asking you again.' },
];

/**
 * First-use choice for ShopBot's automated contracting: confirm all / by criteria / none, plus the buyer's region.
 * EU buyers always confirm each order, because DiscountAI is paid a fee for proposing it.
 */
export function ConfirmationSettings({ compact = false }: { compact?: boolean }) {
  const { state, setBuyerPrefs } = useEngine();
  const p = state.buyerPrefs;
  const [editing, setEditing] = useState(!p);
  const [mode, setMode] = useState<BuyerPrefs['mode']>(p?.mode ?? 'always');
  const [maxPrice, setMaxPrice] = useState(String(p?.maxPrice ?? 9000));
  const [until, setUntil] = useState(p?.untilIso?.slice(0, 10) ?? new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10));
  const [region, setRegion] = useState<BuyerPrefs['region']>(p?.region ?? 'IN');

  if (p && !editing) {
    return (
      <div className={cx('flex flex-wrap items-center gap-3 rounded-lg border border-ink-200 px-3 py-2 text-[13px]', compact && 'text-xs')}>
        <Settings2 size={14} className="text-ink-500" />
        <span>Order confirmation: <b>{p.mode === 'always' ? 'every order' : p.mode === 'criteria' ? `orders over ${inr(p.maxPrice ?? 0)} or after ${new Date(p.untilIso ?? '').toLocaleDateString('en-IN')}` : 'not needed'}</b>{p.region === 'EU' ? ' · EU buyer: every order is confirmed' : ''}</span>
        <button className="ml-auto text-brand-700 underline" onClick={() => setEditing(true)}>Change</button>
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-brand-200 bg-brand-50/40 p-4">
      <p className="font-medium text-ink-900 flex items-center gap-2"><BellRing size={16} /> Before ShopBot places orders for you, choose when it should ask you</p>
      <div className="mt-3 grid gap-2 md:grid-cols-3">
        {MODES.map((m) => (
          <label key={m.id} className={cx('flex items-start gap-2.5 rounded-lg border bg-white p-3 cursor-pointer', mode === m.id ? 'border-brand-500 ring-1 ring-brand-500' : 'border-ink-200')}>
            <input type="radio" name="confirm-mode" className="mt-1 accent-brand-600" checked={mode === m.id} onChange={() => setMode(m.id)} />
            <span><span className="font-medium text-sm">{m.title}</span><span className="block text-xs text-ink-600 mt-0.5">{m.note}</span></span>
          </label>
        ))}
      </div>
      {mode === 'criteria' && (
        <div className="mt-3 flex flex-wrap items-end gap-4 text-sm">
          <label>Auto-confirm orders up to<div className="mt-1 flex items-center gap-1"><span className="text-ink-500">₹</span><input className="input w-32 tabular" inputMode="numeric" value={maxPrice} onChange={(e) => setMaxPrice(e.target.value.replace(/\D/g, ''))} /></div></label>
          <label>Until<input type="date" className="input mt-1 w-44" value={until} onChange={(e) => setUntil(e.target.value)} /></label>
        </div>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
        <label htmlFor="region">I shop from</label>
        <select id="region" className="input w-auto" value={region} onChange={(e) => setRegion(e.target.value as BuyerPrefs['region'])}>
          <option value="IN">India</option><option value="US">United States</option><option value="EU">European Union</option>
        </select>
      </div>
      {region === 'EU' && <div className="mt-3"><Callout tone="info" title="EU buyers confirm every order">DiscountAI is paid a platform fee for each order it proposes, so EU consumer rules require your confirmation of every order, whichever option you pick. Unconfirmed orders are cancelled; nothing is held.</Callout></div>}
      <div className="mt-4 flex gap-2">
        <button className="btn-primary" disabled={mode === 'criteria' && !(Number(maxPrice) > 0)} onClick={() => {
          setBuyerPrefs({ mode, region, maxPrice: mode === 'criteria' ? Number(maxPrice) : undefined, untilIso: mode === 'criteria' ? new Date(`${until}T23:59:59+05:30`).toISOString() : undefined, chosenAt: new Date().toISOString() });
          setEditing(false);
        }}>Save my choice</button>
        {p && <button className="btn-ghost" onClick={() => setEditing(false)}>Cancel</button>}
      </div>
      <p className="mt-2 text-xs text-ink-500">You get a confirmation of this choice, and you can change it at any time.</p>
    </div>
  );
}
