import { useState } from 'react';
import { ArrowDown, ArrowUp, Cpu, FlaskConical, Loader2, Plus, Trash2 } from 'lucide-react';
import { FIELD_KEY, ORDER_FIELDS, checkTable, type CustomAction, type CustomTable, type FieldType } from '../../sim/dmn-compile';
import { evaluateCustom, type CustomResult } from '../../sim/rules';
import { Badge, Callout, cx } from '../../components/ui';

const ACTIONS: CustomAction[] = ['APPROVE', 'REJECT', 'FLAG'];
const ACTION_LABEL: Record<CustomAction, string> = { APPROVE: 'Approve', REJECT: 'Reject', FLAG: 'Flag' };

/**
 * Custom fields and rules for a mandate. Each field is a FEEL expression over the order (an attribute like
 * `brand`, or a formula like `amount / listPrice * 100`); rules test those fields top to bottom and the first
 * match decides. The table compiles to a DMN 1.3 decision and runs in the rule engine at every auction.
 */
export function CustomRulesEditor({ value, onChange, sample, onSample, mandateId }: {
  value: CustomTable; onChange: (t: CustomTable) => void;
  sample: Record<string, string | number>; onSample: (s: Record<string, string | number>) => void; mandateId: string;
}) {
  const [result, setResult] = useState<CustomResult>();
  const [testing, setTesting] = useState(false);
  const [showDmn, setShowDmn] = useState(false);
  const problems = checkTable(value);
  const set = (patch: Partial<CustomTable>) => { onChange({ ...value, ...patch }); setResult(undefined); };

  const addField = () => {
    let n = value.fields.length + 1; while (value.fields.some((f) => f.key === `field${n}`)) n++;
    set({ fields: [...value.fields, { key: `field${n}`, label: `New field ${n}`, type: 'number', expr: 'amount' }] });
  };
  const updField = (i: number, patch: Partial<CustomTable['fields'][number]>) => {
    const old = value.fields[i];
    const fields = value.fields.map((f, j) => (j === i ? { ...f, ...patch } : f));
    // keep rule tests attached when a field is renamed
    const rules = patch.key && patch.key !== old.key
      ? value.rules.map((r) => { const { [old.key]: v, ...rest } = r.tests; return { ...r, tests: v === undefined ? rest : { ...rest, [patch.key!]: v } }; })
      : value.rules;
    set({ fields, rules });
  };
  const delField = (i: number) => set({ fields: value.fields.filter((_, j) => j !== i), rules: value.rules.map((r) => { const { [value.fields[i].key]: _drop, ...rest } = r.tests; return { ...r, tests: rest }; }) });
  const updRule = (i: number, patch: Partial<CustomTable['rules'][number]>) => set({ rules: value.rules.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  const moveRule = (i: number, d: -1 | 1) => { const r = [...value.rules]; const j = i + d; if (j < 0 || j >= r.length) return; [r[i], r[j]] = [r[j], r[i]]; set({ rules: r }); };

  async function test() {
    setTesting(true);
    setResult(await evaluateCustom(value, mandateId, sample, { showDmn: true }));
    setTesting(false);
  }

  return (
    <div className="flex flex-col gap-6">
      <section>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h4 className="font-medium text-ink-900">Fields</h4>
          <p className="text-xs text-ink-500">Each field is a FEEL expression over the order: an attribute (<code>brand</code>) or a formula (<code>amount / listPrice * 100</code>).</p>
        </div>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr><th className="th">Name</th><th className="th">Label</th><th className="th">Type</th><th className="th">Expression</th><th className="th w-10" /></tr></thead>
            <tbody>
              {value.fields.map((f, i) => (
                <tr key={i}>
                  <td className="td"><input aria-label="Field name" className={cx('input py-1.5 font-mono w-40', !FIELD_KEY.test(f.key) && 'ring-1 ring-red-400')} value={f.key} onChange={(e) => updField(i, { key: e.target.value.replace(/\s/g, '') })} /></td>
                  <td className="td"><input aria-label="Field label" className="input py-1.5" value={f.label} onChange={(e) => updField(i, { label: e.target.value })} /></td>
                  <td className="td"><select aria-label="Field type" className="input py-1.5" value={f.type} onChange={(e) => updField(i, { type: e.target.value as FieldType })}>{['number', 'string', 'boolean'].map((t) => <option key={t}>{t}</option>)}</select></td>
                  <td className="td"><input aria-label="Field expression" list="order-attrs" className="input py-1.5 font-mono min-w-[220px]" value={f.expr} onChange={(e) => updField(i, { expr: e.target.value })} /></td>
                  <td className="td"><button className="btn-ghost px-2" aria-label={`Remove field ${f.key}`} onClick={() => delField(i)}><Trash2 size={14} /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <datalist id="order-attrs">{ORDER_FIELDS.map((o) => <option key={o.key} value={o.key}>{o.label}</option>)}</datalist>
        </div>
        <button className="btn-secondary mt-3" onClick={addField} disabled={value.fields.length >= 12}><Plus size={14} /> Add field</button>
        <p className="mt-2 text-xs text-ink-500">Order attributes: {ORDER_FIELDS.map((o) => o.key).join(', ')}.</p>
      </section>

      <section>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h4 className="font-medium text-ink-900">Rules</h4>
          <p className="text-xs text-ink-500">Top to bottom; the first matching row decides. A test is FEEL: <code>&gt; 9000</code>, <code>[0..6]</code>, <code>"Electronics Store A","Marketplace A"</code>, <code>not("Gaming")</code>, <code>true</code>. Leave a cell empty for “any”.</p>
        </div>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr><th className="th w-10">#</th>{value.fields.map((f) => <th key={f.key} className="th font-mono">{f.key}</th>)}<th className="th">Outcome</th><th className="th">Message</th><th className="th w-28" /></tr></thead>
            <tbody>
              {value.rules.map((r, i) => (
                <tr key={i}>
                  <td className="td text-ink-400 tabular">{i + 1}</td>
                  {value.fields.map((f) => (
                    <td key={f.key} className="td"><input aria-label={`Rule ${i + 1} test on ${f.key}`} className="input py-1.5 font-mono min-w-[110px]" placeholder="any" value={r.tests[f.key] ?? ''} onChange={(e) => updRule(i, { tests: { ...r.tests, [f.key]: e.target.value } })} /></td>
                  ))}
                  <td className="td"><select aria-label={`Rule ${i + 1} outcome`} className="input py-1.5" value={r.action} onChange={(e) => updRule(i, { action: e.target.value as CustomAction })}>{ACTIONS.map((a) => <option key={a} value={a}>{ACTION_LABEL[a]}</option>)}</select></td>
                  <td className="td"><input aria-label={`Rule ${i + 1} message`} className="input py-1.5 min-w-[200px]" value={r.message} onChange={(e) => updRule(i, { message: e.target.value })} /></td>
                  <td className="td whitespace-nowrap">
                    <button className="btn-ghost px-1.5" aria-label={`Move rule ${i + 1} up`} onClick={() => moveRule(i, -1)}><ArrowUp size={14} /></button>
                    <button className="btn-ghost px-1.5" aria-label={`Move rule ${i + 1} down`} onClick={() => moveRule(i, 1)}><ArrowDown size={14} /></button>
                    <button className="btn-ghost px-1.5" aria-label={`Remove rule ${i + 1}`} onClick={() => set({ rules: value.rules.filter((_, j) => j !== i) })}><Trash2 size={14} /></button>
                  </td>
                </tr>
              ))}
              <tr className="bg-ink-50">
                <td className="td text-ink-400">—</td>
                <td className="td text-ink-500" colSpan={value.fields.length}>If no rule matches</td>
                <td className="td"><select aria-label="Default outcome" className="input py-1.5" value={value.defaultAction} onChange={(e) => set({ defaultAction: e.target.value as CustomAction })}>{ACTIONS.map((a) => <option key={a} value={a}>{ACTION_LABEL[a]}</option>)}</select></td>
                <td className="td text-ink-500" colSpan={2}>Default outcome</td>
              </tr>
            </tbody>
          </table>
        </div>
        <button className="btn-secondary mt-3" onClick={() => set({ rules: [...value.rules, { tests: {}, action: 'REJECT', message: '' }] })} disabled={value.rules.length >= 40}><Plus size={14} /> Add rule</button>
      </section>

      <section className="rounded-xl border border-ink-200 p-4">
        <h4 className="font-medium text-ink-900 flex items-center gap-2"><FlaskConical size={16} /> Test against a sample order</h4>
        <div className="mt-3 grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {ORDER_FIELDS.map((o) => (
            <label key={o.key} className="text-xs text-ink-600">
              <span className="font-mono">{o.key}</span>
              <input className="input mt-1 py-1.5" inputMode={o.type === 'number' ? 'decimal' : 'text'} value={sample[o.key] ?? ''}
                onChange={(e) => onSample({ ...sample, [o.key]: o.type === 'number' ? Number(e.target.value.replace(/[^\d.-]/g, '')) || 0 : e.target.value })} />
            </label>
          ))}
        </div>
        {problems.length > 0 && <p className="mt-3 text-xs text-red-700">{problems.join(' ')}</p>}
        <button className="btn-primary mt-4" onClick={test} disabled={testing || problems.length > 0}>
          {testing ? <><Loader2 size={15} className="animate-spin" /> Running in the rule engine…</> : <><Cpu size={15} /> Run in rule engine</>}
        </button>
        {result && (
          <div className="mt-4 flex flex-col gap-3">
            {result.error
              ? <Callout tone="warn" title="Rules not accepted">{result.error}</Callout>
              : (
                <div className={cx('rounded-lg border p-3 text-[13px]', result.action === 'REJECT' ? 'border-red-200 bg-red-50/60' : 'border-exchange-100 bg-exchange-50/50')}>
                  <p className="flex flex-wrap items-center gap-2 font-medium text-ink-900">
                    <Cpu size={14} className="text-ink-500" /> {result.engine}
                    <Badge tone={result.source === 'edge' ? 'blue' : 'purple'}>{result.source === 'exchange' ? 'exchange · Fluxnova DMN' : result.source === 'fluxnova' ? 'FINOS Fluxnova' : 'edge function'}</Badge>
                    <Badge tone={result.action === 'REJECT' ? 'red' : result.action === 'FLAG' ? 'amber' : 'green'}>{ACTION_LABEL[result.action ?? 'APPROVE']}</Badge>
                    <span className="text-xs text-ink-500 tabular">{result.ms} ms</span>
                  </p>
                  <p className="mt-1 text-ink-700">{result.message}</p>
                  {result.note && <p className="mt-1 text-xs text-ink-500">{result.note}</p>}
                </div>
              )}
            {result.dmn && (
              <div>
                <button className="text-xs font-medium text-brand-700 underline" onClick={() => setShowDmn(!showDmn)}>{showDmn ? 'Hide' : 'Show'} the compiled DMN 1.3 decision table</button>
                {showDmn && <pre className="mt-2 max-h-80 overflow-auto rounded-lg bg-ink-950 p-3 text-[11.5px] text-ink-100">{result.dmn}</pre>}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
