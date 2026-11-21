import { useEffect, useState } from 'react';
import { AlertTriangle, ChevronRight, ShieldCheck, ShieldX, Wand2 } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { tamper, verifyChain, type ChainCheck } from '../../sim/crypto';
import { dateTime, shortHash, inr } from '../../sim/format';
import type { ProofKind, ProofObject } from '../../sim/types';
import { Badge, Callout, Card, Empty, JsonView, KV, PageHeader, cx } from '../../components/ui';
import { EvidenceView } from './EvidenceView';
import { LiveEvidence } from './LiveEvidence';
import { LIVE } from '../../api/exchange';

const META: Record<ProofKind, { phase: string; by: string; purpose: string }> = {
  POI: { phase: '1 · Buyer intent', by: 'Buyer agent (ECDSA)', purpose: 'Signed purchase intent; anchors the chain' },
  POL: { phase: '2 · Auction result', by: 'OME + buying partner agent', purpose: 'Confirms the SBBA match; links to POI' },
  PRL: { phase: '3 · Funds reserved', by: 'Atomic escrow', purpose: 'Custodian hold confirmed; multi-sig contract deployed' },
  POE: { phase: '4 · Payment captured', by: 'Merchant gateway', purpose: 'Capture success; BIN discount applied' },
  POC: { phase: '5 · Settlement done', by: 'OME + compliance engine', purpose: 'Yield split paid; streamed to the regulator' },
};
const ORDER: ProofKind[] = ['POI', 'POL', 'PRL', 'POE', 'POC'];

export default function ProofChain({ focus }: { focus?: string }) {
  const { state } = useEngine();
  const withProofs = state.requests.filter((r) => r.proofs.length > 0);
  const [picked, setPicked] = useState<string | undefined>(focus);
  const req = withProofs.find((r) => r.id === (picked ?? focus)) ?? withProofs[0];
  const [sel, setSel] = useState<ProofKind>('POI');
  const [tampered, setTampered] = useState(false);
  const [checks, setChecks] = useState<ChainCheck[]>();

  const proofs: ProofObject[] = req ? req.proofs.map((p) => (tampered && p.kind === 'POE' ? tamper(p) : p)) : [];
  const key = `${req?.id}-${req?.proofs.length}-${tampered}`;
  useEffect(() => { if (proofs.length) verifyChain(proofs).then(setChecks); else setChecks(undefined); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [key]);

  const broken = checks?.some((c) => !c.hashOk || !c.linkOk || !c.sigOk);
  const current = proofs.find((p) => p.kind === sel) ?? proofs[0];

  return (
    <>
      <PageHeader eyebrow="Settlement & audit · per-payment evidence" title="Proof chain"
        desc="Every order produces three evidence chains: the buying partner's mandate setup (S), the buyer's purchase from DiscountAI (B) and each buying partner attempt at the merchant (I), plus a reconciliation record joining them. Hashes and signatures are computed live in your browser."
        actions={withProofs.length > 0 && (
          <>
            <label htmlFor="req-pick" className="sr-only">Request</label>
            <select id="req-pick" className="input w-auto" value={req?.id} onChange={(e) => { setPicked(e.target.value); setTampered(false); setSel('POI'); }}>
              {withProofs.map((r) => <option key={r.id} value={r.id}>{r.id} · {r.product} · {r.proofs.length}/5</option>)}
            </select>
          </>
        )} />
      {LIVE && <LiveEvidence initial={focus} />}
      {req?.evidence && <div className="mb-6"><EvidenceView key={req.id} ev={req.evidence} /></div>}
      {req && !req.evidence && <div className="mb-6"><Callout tone="info" title="Evidence chains appear when the order settles">The three per-payment chains are sealed as the order completes.</Callout></div>}
      {!req ? <Empty title="No proofs yet">Submit a marketplace request from the buyer agent to generate a live chain.</Empty> : (
        <details className="card p-4">
          <summary className="cursor-pointer font-medium text-ink-900">Single-chain proofs streamed to the regulator node (POI → POL → PRL → POE → POC)</summary>
        <div className="flex flex-col gap-5 mt-4">
          {/* chain rail */}
          <div className="card p-4 overflow-x-auto">
            <ol className="flex items-stretch gap-2 min-w-[760px]">
              {ORDER.map((k, i) => {
                const p = proofs.find((x) => x.kind === k);
                const c = checks?.find((x) => x.kind === k);
                const bad = c && (!c.hashOk || !c.linkOk || !c.sigOk);
                return (
                  <li key={k} className="flex items-center gap-2 flex-1 min-w-0">
                    <button disabled={!p} onClick={() => setSel(k)}
                      className={cx('flex-1 min-w-0 rounded-xl border p-3 text-left transition-colors disabled:opacity-40',
                        sel === k && p ? 'border-audit-600 ring-1 ring-audit-600 bg-audit-50' : 'border-ink-200 hover:bg-ink-50', bad && 'border-red-400 bg-red-50')}>
                      <div className="flex items-center justify-between"><span className="font-mono font-semibold text-sm">{k}</span>
                        {p ? (bad ? <ShieldX size={15} className="text-red-600" /> : c ? <ShieldCheck size={15} className="text-exchange-600" /> : null) : <span className="text-[10.5px] text-ink-400">pending</span>}</div>
                      <p className="text-[11.5px] text-ink-600 mt-1 truncate">{META[k].phase}</p>
                      {p && <p className="font-mono text-[10.5px] text-ink-400 mt-1 truncate">{shortHash(p.hash, 6)}</p>}
                    </button>
                    {i < ORDER.length - 1 && <ChevronRight size={16} className="shrink-0 text-ink-300" />}
                  </li>
                );
              })}
            </ol>
          </div>

          {broken ? (
            <Callout tone="warn" title="Chain integrity broken · settlement halted">The POE amount no longer matches its hash and signature, so its link fails. In production this halts settlement and sends a compliance alert to the SEBI/RBI regulator node within 500 ms.</Callout>
          ) : checks && proofs.length === 5 ? (
            <Callout tone="good" title="Chain verified">POI → POL → PRL → POE → POC: every hash recomputes, every link matches and all five ECDSA P-256 signatures verify.</Callout>
          ) : null}

          <div className="grid gap-5 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
            {current && (
              <Card title={<span className="flex items-center gap-2"><span className="font-mono">{current.kind}</span> {current.title}</span>} sub={`${META[current.kind].purpose} · signed by ${META[current.kind].by}`}>
                <JsonView value={current.payload} />
                <div className="mt-4">
                  <KV mono rows={[
                    ['SHA-256', <span key="h" className="break-all">{current.hash}</span>],
                    ['hash_prev', <span key="p" className="break-all">{String(current.payload.hash_prev ?? '— chain head')}</span>],
                    ['ECDSA signature', <span key="s" className="break-all">{shortHash(current.signature, 18)}</span>],
                    ['Created', dateTime(current.createdAt)],
                  ]} />
                </div>
              </Card>
            )}
            <div className="flex flex-col gap-5">
              <Card title="Integrity checks" sub="Chain verification">
                <table className="w-full text-sm">
                  <thead><tr><th className="th">Proof</th><th className="th">Hash</th><th className="th">Link</th><th className="th">Signature</th></tr></thead>
                  <tbody>
                    {(checks ?? []).map((c) => (
                      <tr key={c.kind}>
                        <td className="td font-mono">{c.kind}<span className="block font-sans text-[11px] text-ink-500">{c.note}</span></td>
                        {[c.hashOk, c.linkOk, c.sigOk].map((ok, i) => <td key={i} className="td">{ok ? <Badge tone="green">pass</Badge> : <Badge tone="red">fail</Badge>}</td>)}
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-4 flex flex-wrap gap-2">
                  {proofs.some((p) => p.kind === 'POE') && (
                    tampered
                      ? <button className="btn-secondary" onClick={() => setTampered(false)}>Restore original POE</button>
                      : <button className="btn-secondary" onClick={() => setTampered(true)}><Wand2 size={14} /> Tamper test: add ₹1,000 to POE</button>
                  )}
                </div>
                {tampered && <p className="mt-2 text-xs text-amber-800 flex items-center gap-1.5"><AlertTriangle size={13} /> Local demo only. The stored chain is unchanged.</p>}
              </Card>
              {req.split && (
                <Card title="Settlement" sub={`${req.product} · ${req.route ?? ''}`}>
                  <KV rows={[
                    ['List price P', inr(req.split.list)],
                    [`Merchant charged buying partner card A (−${req.split.discountPct}%)`, inr(req.split.A)],
                    ['Buyer paid B (captured on shipment)', inr(req.split.B)],
                    ['Buying partner payout A + Y', `${inr(req.split.payout)} (yield ${inr(req.split.Y)})`],
                    ['Platform fee F', `${inr(req.split.F)} (${inr(req.split.guaranteeFund)} to Guarantee Fund)`],
                    ['Buyer saved S = P − B', inr(req.split.buyerSaves)],
                  ]} />
                </Card>
              )}
            </div>
          </div>
        </div>
        </details>
      )}
    </>
  );
}
