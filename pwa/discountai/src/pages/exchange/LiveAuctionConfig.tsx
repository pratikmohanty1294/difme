import { useEffect, useState } from 'react';
import { RotateCcw, Save } from 'lucide-react';
import { exchange, ExchangeError, type ExchangeSettings, type SettingsView } from '../../api/exchange';
import { Callout, Card, PageHeader } from '../../components/ui';

type Key = keyof ExchangeSettings;
const FIELDS: { key: Key; label: string; unit?: string; step?: number; help: string }[] = [
  { key: 'priceWeight', label: 'Price weight', step: 0.05, help: 'Share of the score from the yield asked (lower ask scores higher).' },
  { key: 'reputationWeight', label: 'Reputation weight', step: 0.05, help: 'Share from the reputation the exchange tracks (never the bidder’s own claim).' },
  { key: 'latencyWeight', label: 'Latency weight', step: 0.05, help: 'Share from bid latency, capped at the latency limit below.' },
  { key: 'windowMs', label: 'Batch window', unit: 'ms', help: 'How long each auction collects bids before it closes.' },
  { key: 'latencyMaxMs', label: 'Latency cap (L max)', unit: 'ms', help: 'Bids slower than this score zero on latency.' },
  { key: 'backups', label: 'Hot backups', help: 'Runners-up kept for silent failover if the winner’s card declines.' },
  { key: 'minRiskScore', label: 'Minimum buyer risk score', help: 'Intents below this are refused before broadcast (RISK_TOO_LOW).' },
  { key: 'slashIso51Bps', label: 'Slash on insufficient funds (ISO 51)', unit: 'bps of A', help: '200 bps = 2 % of the card amount.' },
  { key: 'slashOtherBps', label: 'Slash on other declines', unit: 'bps of A', help: '50 bps = 0.5 %.' },
  { key: 'mandateDepositBps', label: 'Mandate deposit rate', unit: 'bps of budget', help: 'The deposit is the larger of this and the collateral on the largest order.' },
  { key: 'returnWindowDays', label: 'Return window', unit: 'days', help: 'Yield is released this long after proof of delivery.' },
];

/** Exchange operator, live builds: the settings the exchange actually applies, with every change logged. */
export default function LiveAuctionConfig() {
  const [view, setView] = useState<SettingsView>();
  const [draft, setDraft] = useState<ExchangeSettings>();
  const [by, setBy] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string; problems?: string[] }>();
  const load = () => exchange.settings().then((v) => { setView(v); setDraft(v.settings); },
    (e) => setMsg({ ok: false, text: e instanceof ExchangeError && e.status === 0 ? 'The exchange is unreachable.' : String(e instanceof Error ? e.message : e) }));
  useEffect(() => { load(); }, []);
  if (!view || !draft) return <><PageHeader eyebrow="Exchange OME · rules" title="Auction config" desc="Loading the exchange's settings…" />{msg && <Callout tone="warn" title="Couldn't load">{msg.text}</Callout>}</>;

  const changed = FIELDS.filter((f) => draft[f.key] !== view.settings[f.key]);
  const sum = draft.priceWeight + draft.reputationWeight + draft.latencyWeight;
  const save = async () => {
    try {
      const v = await exchange.updateSettings(draft, by.trim() || undefined);
      setView(v); setDraft(v.settings);
      setMsg({ ok: true, text: `Saved as version ${v.version}. New auctions use these settings; each auction's evidence records the version it ran under.` });
    } catch (e) {
      const b = e instanceof ExchangeError ? (e.body as { error?: string; problems?: string[] } | undefined) : undefined;
      setMsg({ ok: false, text: b?.error ?? (e instanceof Error ? e.message : String(e)), problems: b?.problems });
    }
  };

  return (
    <>
      <PageHeader eyebrow="Exchange OME · rules" title="Auction config"
        desc={`The settings the exchange applies to ${view.pack}, version ${view.version}. Changes take effect from the next auction and are logged with who made them.`} />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Card title="Settings" sub={`Scoring weights must add up to 1 (now ${sum.toFixed(2)})`}>
          <div className="grid gap-4 sm:grid-cols-2">
            {FIELDS.map((f) => (
              <label key={f.key} className="text-sm">
                <span className="font-medium text-ink-900">{f.label}{f.unit ? <span className="text-ink-500 font-normal"> · {f.unit}</span> : null}</span>
                <input aria-label={f.label} type="number" step={f.step ?? 1} className="input mt-1" value={draft[f.key]}
                  onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value === '' ? 0 : Number(e.target.value) })} />
                <span className="block text-xs text-ink-500 mt-0.5">{f.help} Default {view.defaults[f.key]}.</span>
              </label>
            ))}
          </div>
          <label className="text-sm block mt-4"><span className="font-medium">Your name (for the change log)</span>
            <input aria-label="Changed by" className="input mt-1 max-w-xs" value={by} maxLength={60} onChange={(e) => setBy(e.target.value)} placeholder="operator" /></label>
          <div className="mt-4 flex flex-wrap gap-2">
            <button className="btn-primary" disabled={!changed.length} onClick={save}><Save size={15} /> Save {changed.length ? `${changed.length} change${changed.length > 1 ? 's' : ''}` : ''}</button>
            <button className="btn-secondary" onClick={() => setDraft(view.defaults)}><RotateCcw size={14} /> Reset to defaults</button>
          </div>
          {msg && <div className="mt-3"><Callout tone={msg.ok ? 'good' : 'warn'} title={msg.ok ? 'Saved' : 'Not saved'}>{msg.text}{msg.problems && <ul className="list-disc pl-5 mt-1">{msg.problems.map((p) => <li key={p}>{p}</li>)}</ul>}</Callout></div>}
          <div className="mt-4"><Callout tone="info" title="Not applied by the live exchange">
            The prototype's per-flow weights (A card, B stablecoin, C UPI), quote timeout, passkey re-check window, Vickrey pricing and new-node reliability aren't used by the exchange yet: it scores every bid with the one weight set above.
          </Callout></div>
        </Card>
        <Card title="Change log" sub={`${view.log.length} changes`} pad={false}>
          <div className="overflow-x-auto max-h-[640px]">
            <table className="w-full text-sm">
              <thead><tr><th className="th">When</th><th className="th">By</th><th className="th">Setting</th><th className="th">From → to</th><th className="th">v</th></tr></thead>
              <tbody>
                {[...view.log].reverse().map((c, i) => (
                  <tr key={i}><td className="td text-xs tabular">{new Date(c.atMs).toLocaleString('en-IN', { dateStyle: 'short', timeStyle: 'short' })}</td>
                    <td className="td text-xs">{c.by}</td><td className="td font-mono text-xs">{c.field}</td><td className="td text-xs tabular">{c.from} → {c.to}</td><td className="td text-xs">{c.version}</td></tr>
                ))}
                {view.log.length === 0 && <tr><td className="td text-ink-500" colSpan={5}>No changes yet: the vertical's defaults apply.</td></tr>}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}
