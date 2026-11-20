import { Cpu } from 'lucide-react';
import type { RuleResult } from '../sim/rules';
import { Badge, cx } from './ui';

const LABEL: Record<RuleResult['decision'], string> = {
  mandate_validation: 'mandate_validation',
  buyer_intent_eligibility: 'buyer_intent_eligibility',
  investor_eligibility: 'investor_eligibility',
};

export const SOURCE: Record<RuleResult['source'], string> = {
  edge: 'DMN engine · edge function',
  fluxnova: 'FINOS Fluxnova',
  local: 'local fallback',
};

/** Shows a DMN decision: which engine decided, how long it took, and every rule that fired. */
export function RuleResultView({ r }: { r: RuleResult }) {
  return (
    <div className={cx('rounded-lg border p-3 text-[13px]', r.ok ? 'border-exchange-100 bg-exchange-50/50' : 'border-red-200 bg-red-50/60')}>
      <p className="flex flex-wrap items-center gap-2 font-medium text-ink-900">
        <Cpu size={14} className="text-ink-500" />
        Rule engine · <span className="font-mono text-xs">{LABEL[r.decision]}.dmn</span>
        <Badge tone={r.source === 'local' ? 'gray' : 'purple'}>{SOURCE[r.source]}</Badge>
        <Badge tone={r.ok ? 'green' : 'red'}>{r.ok ? 'Passed' : 'Blocked'}</Badge>
        <span className="text-xs text-ink-500 tabular">{r.ms} ms</span>
      </p>
      {r.note && <p className="mt-1 text-xs text-ink-500">{r.note.replace(/\.$/, '')}; the same DMN rules ran {r.source === 'local' ? 'in your browser' : 'on the edge function'}.</p>}
      {r.hits.length > 0 && (
        <ul className="mt-2 flex flex-col gap-1">
          {r.hits.map((h) => (
            <li key={h.code} className="flex items-start gap-2">
              <Badge tone={h.severity === 'BLOCK' ? 'red' : h.severity === 'WARN' ? 'amber' : 'blue'}>{h.severity}</Badge>
              <span className="text-ink-700">{h.message} <span className="font-mono text-[11px] text-ink-400">{h.code}</span></span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
