// The attributes rule expressions can use: the exchange's catalog in live builds (built-ins + the operator's uploaded
// master list), the built-in demo list otherwise.
import { useEffect, useState } from 'react';
import { LIVE, exchange } from '../api/exchange';
import { ORDER_FIELDS, type FieldType } from './dmn-compile';

export interface RuleAttribute { key: string; type: FieldType; label: string; example: string | number | boolean; builtIn?: boolean }

const BUILT_IN_LABEL: Record<string, string> = {
  amount: 'Card amount A (₹)', listPrice: 'List price P (₹)', discountPct: 'Card offer (%)', yieldPct: 'Your yield ask (% of A)',
  hour: 'Hour of day (IST, 0–23)', weekday: 'Weekday', buyerRiskScore: 'Buyer risk score',
};

function exampleOf(type: FieldType, raw?: string | null): string | number | boolean {
  if (type === 'number') return raw != null && raw !== '' && !isNaN(Number(raw)) ? Number(raw) : 0;
  if (type === 'boolean') return raw === 'true' || raw === '1' || raw === 'yes';
  return raw ?? '';
}

export function useRuleAttributes(): { attrs: RuleAttribute[]; live: boolean; error?: string; reload: () => void } {
  const fallback = ORDER_FIELDS.map((f) => ({ ...f, builtIn: Object.keys(BUILT_IN_LABEL).includes(f.key) }));
  const [attrs, setAttrs] = useState<RuleAttribute[]>(fallback);
  const [error, setError] = useState<string>();
  const [n, setN] = useState(0);
  useEffect(() => {
    if (!LIVE) return;
    exchange.attributes().then((c) => {
      const demo = new Map(ORDER_FIELDS.map((f) => [f.key, f]));
      setAttrs([
        ...c.builtIn.map((b) => ({ key: b.key, type: b.type as FieldType, label: BUILT_IN_LABEL[b.key] ?? b.key, example: demo.get(b.key)?.example ?? 0, builtIn: true })),
        ...c.attributes.map((a) => ({ key: a.key, type: a.type, label: a.label || a.key, example: exampleOf(a.type, a.example ?? (demo.get(a.key)?.example as string | undefined)) })),
      ]);
      setError(undefined);
    }, (e) => setError(e instanceof Error ? e.message : String(e)));
  }, [n]);
  return { attrs, live: LIVE, error, reload: () => setN((x) => x + 1) };
}
