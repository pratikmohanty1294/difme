import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, ShieldCheck, ShieldX, Wand2 } from 'lucide-react';
import { verify, verifyRecon, type Chain, type ChainVerdict, type Evidence } from '../../sim/evidence';
import { shortHash } from '../../sim/format';
import { Badge, Callout, Card, JsonView, cx } from '../../components/ui';

const rupees = (paise: unknown) => `₹${(Number(paise) / 100).toLocaleString('en-IN')}`;
const KIND_NOTE: Record<Chain['kind'], string> = {
  S: 'Mandate setup · sealed when the the card network instruction is registered',
  B: 'Buyer sale · DiscountAI is merchant of record · sealed at the buyer hold',
  I: 'Buying partner purchase at the merchant · sealed at the issuer’s answer',
};

/** The three per-payment evidence chains of one order, verified live in the browser. */
export function EvidenceView({ ev }: { ev: Evidence }) {
  const [tampered, setTampered] = useState(false);
  const winner = ev.I.find((c) => c.converting);
  const chains: Chain[] = useMemo(() => {
    const all = [ev.S, ev.B, ...ev.I];
    if (!tampered || !winner) return all;
    // Tamper test: add ₹1,000 to the buying partner authorisation without re-hashing.
    return all.map((c) => (c.id !== winner.id ? c : { ...c, artifacts: c.artifacts.map((a) => (a.type === 'authorization' ? { ...a, payload: { ...a.payload, amount: Number(a.payload.amount) + 100000 } } : a)) }));
  }, [ev, tampered, winner]);
  const [sel, setSel] = useState(ev.B.id);
  const [art, setArt] = useState(0);
  const [verdicts, setVerdicts] = useState<Record<string, ChainVerdict>>({});
  const [recon, setRecon] = useState<{ identity: boolean; linked: boolean; sigs: boolean }>();

  useEffect(() => {
    let live = true;
    (async () => {
      const v = Object.fromEntries(await Promise.all(chains.map(async (c) => [c.id, await verify(c)] as const)));
      const w = chains.find((c) => c.id === winner?.id);
      const r = ev.recon && w ? await verifyRecon(ev.recon, chains.find((c) => c.kind === 'B')!, w) : undefined;
      if (live) { setVerdicts(v); setRecon(r); }
    })();
    return () => { live = false; };
  }, [chains, ev.recon, winner]);

  const pass = (v?: ChainVerdict) => !!v && v.hashes && v.links && v.must1 !== false && v.seal && v.witnesses;
  const allOk = chains.every((c) => pass(verdicts[c.id])) && (!ev.recon || (recon?.identity && recon.linked && recon.sigs));
  const cur = chains.find((c) => c.id === sel) ?? chains[0];
  const a = cur.artifacts[Math.min(art, cur.artifacts.length - 1)];

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {chains.map((c) => {
          const v = verdicts[c.id];
          return (
            <button key={c.id} onClick={() => { setSel(c.id); setArt(0); }}
              className={cx('rounded-xl border p-3 text-left transition-colors', sel === c.id ? 'border-audit-600 ring-1 ring-audit-600 bg-audit-50' : 'border-ink-200 hover:bg-ink-50', v && !pass(v) && 'border-red-400 bg-red-50')}>
              <div className="flex items-center justify-between">
                <span className="font-mono font-semibold text-sm">Chain {c.kind}</span>
                {v ? (pass(v) ? <ShieldCheck size={15} className="text-exchange-600" /> : <ShieldX size={15} className="text-red-600" />) : <span className="text-[10.5px] text-ink-400">checking</span>}
              </div>
              <p className="text-[12px] text-ink-700 mt-1">{c.label}</p>
              <p className="text-[11px] text-ink-500 mt-0.5">{c.artifacts.length} artifacts{c.kind === 'I' ? (c.converting ? ' · converting' : ' · non-converting') : ''}</p>
            </button>
          );
        })}
      </div>

      {Object.keys(verdicts).length > 0 && (allOk
        ? <Callout tone="good" title="All chains verify">Every artifact re-hashes and links; on each chain the authorisation equals the cart total (AEP MUST-1); every operator seal and witness counter-signature verifies ({ev.B.seal?.operator.alg}); the reconciliation record proves B = A + Y + F across the two payments.</Callout>
        : <Callout tone="warn" title="Evidence broken: settlement halted">{chains.flatMap((c) => verdicts[c.id]?.notes.map((n) => `Chain ${c.kind}: ${n}`) ?? []).slice(0, 4).join(' ')} {recon && !recon.linked ? 'The reconciliation record no longer matches the chains.' : ''}</Callout>)}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
        <Card title={`Chain ${cur.kind} · ${cur.id}`} sub={KIND_NOTE[cur.kind]}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr><th className="th">#</th><th className="th">Artifact</th><th className="th">Signal</th><th className="th">Hash</th><th className="th">Links to</th></tr></thead>
              <tbody>
                {cur.artifacts.map((x) => (
                  <tr key={x.seq} onClick={() => setArt(x.seq)} className={cx('cursor-pointer', art === x.seq && 'bg-audit-50')}>
                    <td className="td tabular text-ink-500">{x.seq}</td>
                    <td className="td font-medium">{x.type}{cur.seal?.seq === x.seq && <span className="ml-1.5"><Badge tone="purple">seal</Badge></span>}{x.post_seal && <span className="ml-1.5 text-[11px] text-ink-500">post-seal</span>}</td>
                    <td className="td text-ink-600">{x.signal}</td>
                    <td className="td font-mono text-[11.5px]">{shortHash(x.hash, 6)}</td>
                    <td className="td font-mono text-[11.5px] text-ink-500">{x.prev_hash ? shortHash(x.prev_hash, 6) : 'chain head'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {a && <div className="mt-4"><p className="label mb-1">{a.type} payload (amounts in paise)</p><JsonView value={a.payload} /></div>}
        </Card>
        <div className="flex flex-col gap-5">
          <Card title="Seal and witnesses" sub="One operator signature per chain; independent parties counter-sign">
            {cur.seal ? (
              <ul className="flex flex-col gap-2 text-[13px]">
                {[cur.seal.operator, ...cur.seal.witnesses].map((s) => (
                  <li key={s.by} className="flex flex-wrap items-center gap-2">
                    <Badge tone={s.role === 'operator' ? 'purple' : 'blue'}>{s.role}</Badge><span className="font-medium">{s.by}</span>
                    <span className="font-mono text-[11px] text-ink-500">{s.alg} · {shortHash(s.signature, 8)}</span>
                  </li>
                ))}
              </ul>
            ) : <p className="text-sm text-ink-500">Not sealed.</p>}
            {verdicts[cur.id] && (
              <table className="mt-4 w-full text-sm">
                <tbody>
                  {([['Hashes recompute', verdicts[cur.id].hashes], ['Links unbroken', verdicts[cur.id].links], ['Authorisation = cart total (AEP MUST-1)', verdicts[cur.id].must1], ['Operator seal', verdicts[cur.id].seal], ['Witness counter-signatures', verdicts[cur.id].witnesses]] as const).map(([t, ok]) => (
                    <tr key={t}><td className="td">{t}</td><td className="td text-right">{ok === null ? <Badge tone="gray">n/a</Badge> : ok ? <Badge tone="green">pass</Badge> : <Badge tone="red">fail</Badge>}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="mt-4">
              {winner && (tampered
                ? <button className="btn-secondary" onClick={() => setTampered(false)}>Restore the original chain</button>
                : <button className="btn-secondary" onClick={() => setTampered(true)}><Wand2 size={14} /> Tamper test: add ₹1,000 to the buying partner authorisation</button>)}
              {tampered && <p className="mt-2 text-xs text-amber-800 flex items-center gap-1.5"><AlertTriangle size={13} /> Local demo only. The stored chains are unchanged.</p>}
            </div>
          </Card>
          {ev.recon && (
            <Card title="Reconciliation record" sub="DiscountAI extension: joins the two payments, outside AEP verification">
              <table className="w-full text-sm">
                <tbody>
                  <tr><td className="td">B (buyer paid)</td><td className="td text-right tabular">{rupees(ev.recon.B)}</td></tr>
                  <tr><td className="td">A (card charged) + Y (yield) + F (fee)</td><td className="td text-right tabular">{rupees(ev.recon.A)} + {rupees(ev.recon.Y)} + {rupees(ev.recon.F)}</td></tr>
                  <tr><td className="td">B = A + Y + F</td><td className="td text-right">{recon ? (recon.identity ? <Badge tone="green">holds</Badge> : <Badge tone="red">fails</Badge>) : '…'}</td></tr>
                  <tr><td className="td">Points at both authorisations</td><td className="td text-right">{recon ? (recon.linked ? <Badge tone="green">pass</Badge> : <Badge tone="red">fail</Badge>) : '…'}</td></tr>
                  <tr><td className="td">Signed by DiscountAI, counter-signed by Agent PA/PG</td><td className="td text-right">{recon ? (recon.sigs ? <Badge tone="green">pass</Badge> : <Badge tone="red">fail</Badge>) : '…'}</td></tr>
                </tbody>
              </table>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
