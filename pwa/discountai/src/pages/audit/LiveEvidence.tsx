import { useEffect, useState } from 'react';
import { ShieldCheck, ShieldX } from 'lucide-react';
import { exchange, ExchangeError, type EvidenceView } from '../../api/exchange';
import { shortHash } from '../../sim/format';
import { Badge, Callout, Card, JsonView } from '../../components/ui';

/** Exchange operator: the server's evidence chain for any auction, verified by the exchange against its published keys. */
export function LiveEvidence({ initial }: { initial?: string }) {
  const [id, setId] = useState(initial && /^\d+$/.test(initial) ? initial : '');
  const [ev, setEv] = useState<EvidenceView>();
  const [err, setErr] = useState<string>();
  const [busy, setBusy] = useState(false);
  const load = async () => {
    const n = Number(id.replace(/^auc_/, ''));
    if (!Number.isInteger(n) || n <= 0) return setErr('Enter an auction number.');
    setBusy(true); setErr(undefined); setEv(undefined);
    try { setEv(await exchange.evidence(n)); }
    catch (e) { setErr(e instanceof ExchangeError && e.status === 404 ? `No evidence chain for auction ${n}.` : String(e instanceof Error ? e.message : e)); }
    finally { setBusy(false); }
  };
  // Opened from the operator's order list (#/audit/<auction>): load that auction straight away.
  useEffect(() => { if (id) load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  const v = ev?.verdict;
  const ok = v && v.hashes && v.links && v.seal && v.witnesses;
  return (
    <Card className="mb-6" title="Live evidence (exchange)" sub="The server's chain for an auction, verified against the exchange's published keys">
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); load(); }}>
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-ink-900">Auction</span>
          <input className="input w-48" value={id} onChange={(e) => setId(e.target.value)} placeholder="e.g. 1042" inputMode="numeric" />
        </label>
        <button className="btn-primary" disabled={busy}>{busy ? 'Loading…' : 'Load evidence'}</button>
      </form>
      {err && <div className="mt-3"><Callout tone="warn" title="Not loaded">{err}</Callout></div>}
      {ev && v && (
        <div className="mt-4 flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            {ok ? <ShieldCheck className="text-emerald-600" aria-hidden /> : <ShieldX className="text-rose-600" aria-hidden />}
            <span className="font-medium">{ok ? 'Chain verifies' : 'Chain does not verify'}</span>
            <Badge tone={v.hashes ? 'green' : 'red'}>hashes</Badge>
            <Badge tone={v.links ? 'green' : 'red'}>links</Badge>
            <Badge tone={v.seal ? 'green' : 'red'}>operator seal</Badge>
            <Badge tone={v.witnesses ? 'green' : 'red'}>witness</Badge>
          </div>
          {v.notes.length > 0 && <Callout tone="warn" title="Notes">{v.notes.join('; ')}</Callout>}
          <ol className="flex flex-col gap-2">
            {(ev.artifacts ?? []).map((a) => (
              <li key={a.seq} className="rounded border border-ink-100 p-3">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-mono">#{a.seq}</span>
                  <span className="font-medium">{a.type}</span>
                  <Badge>{a.signal}</Badge>
                  {a.postSeal && <Badge tone="amber">after seal</Badge>}
                  <span className="font-mono text-ink-500 ml-auto">{shortHash(a.hash)}</span>
                </div>
                <details className="mt-2"><summary className="cursor-pointer text-sm text-ink-600">Payload</summary><JsonView value={a.payload} /></details>
              </li>
            ))}
          </ol>
        </div>
      )}
    </Card>
  );
}
