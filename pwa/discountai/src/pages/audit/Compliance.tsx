import { Landmark } from 'lucide-react';
import { useEngine } from '../../sim/engine';
import { time } from '../../sim/format';
import { Badge, Card, PageHeader, Stat, cx } from '../../components/ui';

const FRAMEWORK = [
  { reg: 'RBI · payments', items: ['PA licence (₹15 Cr net worth); PA-CB for non-INR flows', 'UPI Circle / Reserve, OTM and SBMD mandates; UPI PIN mandatory', 'CoFT: card numbers stay with the issuer and the card network, and the agent token with the Agent PA/PG; DiscountAI keeps only the mandate ID, offer BIN and bank name; no credential lending', 'Agent purchases with no human present: the buying partner approves one instruction (budget, merchants, goods, up to 7 days) with their passkey. India-issued cards are approved once as a pre-authorised card mandate with AFA at setup, so purchases need no AFA. Agent tokens carry Network token requestor type 08 (Card-network rules: Agent Pay Human Not Present)', 'Buyer auth-only hold captured on shipment; escrow in a nodal account'], shown: 'buyer hold and capture, deposits, network agent tokens, escrow payouts' },
  { reg: 'SEBI · investment & RWA', items: ['SM-REIT tokens as ERC-1400 with a regulated custodian', 'Algo-trading registration for the SBBA algorithm', 'Real-time explainability of SBBA decisions'], shown: 'Score breakdown on every bid in the live auction' },
  { reg: 'IFSCA · global & crypto', items: ['FinTech sandbox for Flow B', 'FEMA + LRS for cross-border settlement', 'Bridge fee split disclosed to all parties'], shown: 'Hybrid route labelled on the winner and in the event log' },
  { reg: 'IoRS · unified oversight', items: ['All five proofs to the regulator node within 500 ms', 'T+2 log availability (Iceberg); OpenLineage audit trail', 'CERT-In reporting within 6 hours'], shown: 'Live proof stream and latency on this page' },
  { reg: 'DPDP Act 2023', items: ['Buyer intent data is sensitive personal data; DPO required', 'Separate consents: discovery, instrument access, mandate, yield sharing', 'All PII stored in India'], shown: 'Granular consents in the buyer request form' },
  { reg: 'FATF / PMLA', items: ['Aadhaar OTP or V-CIP KYC for buying partners; buyer KYC above ₹50,000', 'Travel Rule for crypto flows above ₹50,000', 'STR to FIU-IND; OFAC/UN screening'], shown: 'Not simulated in this prototype' },
];

export default function Compliance() {
  const { state } = useEngine();
  const ev = state.regulator;
  const sorted = ev.map((e) => e.latencyMs).sort((a, b) => a - b);
  const p95 = sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)] : 0;
  const alerts = ev.filter((e) => e.proof === 'ALERT').length;

  return (
    <>
      <PageHeader eyebrow="Settlement & audit · regulatory framework" title="Regulator node"
        desc="The SEBI, RBI and tax-authority view: every proof object streamed in real time, plus the regulatory obligations and where this prototype shows them." />
      <div className="grid gap-4 grid-cols-2 xl:grid-cols-4 mb-5">
        <Stat label="Proofs streamed" value={ev.filter((e) => e.proof !== 'ALERT').length} sub="This session" />
        <Stat label="Stream latency · p95" value={ev.length ? `${p95} ms` : '—'} sub="SLA ≤ 500 ms" tone={p95 <= 500 ? 'good' : 'warn'} />
        <Stat label="Compliance alerts" value={alerts} sub={alerts ? 'Chain halted on failure' : 'None raised'} tone={alerts ? 'warn' : 'good'} />
        <Stat label="Log availability" value="T+2" sub="Iceberg lakehouse · OpenLineage" />
      </div>
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <Card title={<span className="flex items-center gap-2"><Landmark size={15} className="text-audit-600" /> Live proof stream</span>} right={<Badge tone="purple" dot>Streaming</Badge>} pad={false}>
          {ev.length === 0 ? <p className="px-5 py-6 text-sm text-ink-500">Waiting for the first proof. Submit a request from the buyer agent.</p> : (
            <ul className="max-h-[520px] overflow-y-auto divide-y divide-ink-100">
              {ev.map((e, i) => (
                <li key={i} className="rise flex items-center gap-3 px-5 py-2.5 text-[13px]">
                  <span className="font-mono text-[11px] text-ink-400 tabular">{time(e.at)}</span>
                  <Badge tone={e.proof === 'ALERT' ? 'red' : 'purple'}>{e.proof}</Badge>
                  <span className="flex-1 min-w-0 truncate text-ink-800">{e.text}</span>
                  <span className={cx('text-xs tabular', e.latencyMs <= 500 ? 'text-exchange-600' : 'text-red-700')}>{e.latencyMs} ms</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Obligations and coverage" sub="RBI, SEBI, IFSCA and DPDP" pad={false}>
          <ul className="divide-y divide-ink-100">
            {FRAMEWORK.map((f) => (
              <li key={f.reg} className="px-5 py-4">
                <p className="font-medium text-ink-900">{f.reg}</p>
                <ul className="mt-1.5 list-disc pl-5 text-[13px] text-ink-600 space-y-0.5">{f.items.map((i) => <li key={i}>{i}</li>)}</ul>
                <p className="mt-2 text-xs"><span className="text-ink-500">In this prototype: </span><span className={f.shown.startsWith('Not') ? 'text-ink-500' : 'text-audit-600'}>{f.shown}</span></p>
              </li>
            ))}
          </ul>
        </Card>
      </div>
    </>
  );
}
