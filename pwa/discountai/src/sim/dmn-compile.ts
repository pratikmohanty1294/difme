// Compiles a buying partner's custom mandate rules (fields + rules, built in the Smart mandate factory) into a
// standard DMN 1.3 decision table. Shared by the browser (to show the DMN) and the server (which compiles it
// again from the structured input, so no user-written XML ever reaches a rule engine).

/** Order attributes every auction provides to a mandate's custom rules. */
export const ORDER_FIELDS: { key: string; type: FieldType; label: string; example: string | number }[] = [
  { key: 'amount', type: 'number', label: 'Card amount A (₹)', example: 8000 },
  { key: 'listPrice', type: 'number', label: 'List price P (₹)', example: 10000 },
  { key: 'discountPct', type: 'number', label: 'Card offer (%)', example: 20 },
  { key: 'yieldPct', type: 'number', label: 'Your yield ask (% of A)', example: 7.5 },
  { key: 'merchant', type: 'string', label: 'Merchant', example: 'Electronics Store A' },
  { key: 'category', type: 'string', label: 'Goods category', example: 'Electronics' },
  { key: 'brand', type: 'string', label: 'Brand', example: 'Brand H' },
  { key: 'mcc', type: 'string', label: 'Merchant category code', example: '5732' },
  { key: 'quantity', type: 'number', label: 'Quantity', example: 1 },
  { key: 'city', type: 'string', label: 'Delivery city', example: 'Hyderabad' },
  { key: 'pincode', type: 'string', label: 'Delivery PIN code', example: '500032' },
  { key: 'hour', type: 'number', label: 'Hour of day (IST, 0–23)', example: 14 },
  { key: 'weekday', type: 'string', label: 'Weekday', example: 'Mon' },
  { key: 'buyerRiskScore', type: 'number', label: 'Buyer risk score', example: 870 },
];

export type FieldType = 'number' | 'string' | 'boolean';
export type CustomAction = 'APPROVE' | 'REJECT' | 'FLAG';
/** A rule input: a name the buying partner chooses and a FEEL expression over the order attributes. */
export interface CustomField { key: string; label: string; type: FieldType; expr: string }
/** One row: a FEEL unary test per field key ('-' or empty = any), then the outcome. */
export interface CustomRule { tests: Record<string, string>; action: CustomAction; message: string }
export interface CustomTable { fields: CustomField[]; rules: CustomRule[]; defaultAction: CustomAction }

const RESERVED = new Set(['and', 'or', 'not', 'true', 'false', 'null', 'for', 'in', 'if', 'then', 'else', 'some', 'every', 'satisfies', 'instance', 'of', 'function', 'between', 'return']);
export const FIELD_KEY = /^[a-z][A-Za-z0-9_]{0,30}$/;

export function sampleOrder(): Record<string, string | number> {
  return Object.fromEntries(ORDER_FIELDS.map((f) => [f.key, f.example]));
}

/** Structural checks (FEEL syntax is checked by the rule engine). Returns problems in plain words. */
export function checkTable(t: CustomTable): string[] {
  const p: string[] = [];
  if (!t.fields.length) p.push('Add at least one field.');
  if (t.fields.length > 12) p.push('At most 12 fields.');
  if (t.rules.length > 40) p.push('At most 40 rules.');
  const seen = new Set<string>();
  for (const f of t.fields) {
    if (!FIELD_KEY.test(f.key)) p.push(`Field name “${f.key}” must start with a lower-case letter and use only letters, digits or _.`);
    if (RESERVED.has(f.key)) p.push(`“${f.key}” is a reserved word.`);
    if (seen.has(f.key)) p.push(`Field “${f.key}” is defined twice.`);
    seen.add(f.key);
    if (!f.expr.trim()) p.push(`Field “${f.key}” needs an expression, e.g. an order attribute like amount.`);
    if (f.expr.length > 200) p.push(`Field “${f.key}”: expression too long.`);
  }
  t.rules.forEach((r, i) => {
    for (const v of Object.values(r.tests)) if ((v ?? '').length > 200) p.push(`Rule ${i + 1}: a test is too long.`);
    if ((r.message ?? '').length > 160) p.push(`Rule ${i + 1}: message too long.`);
  });
  return p;
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const feelString = (s: string) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/** DMN decision id for a mandate's custom table (letters, digits and _ only). */
export const customDecisionId = (mandateId: string) => `mandate_custom_${mandateId.replace(/[^A-Za-z0-9]/g, '_')}`.slice(0, 64);

/** Compiles to DMN 1.3 XML. Hit policy FIRST: rules run top to bottom and the first match decides. */
export function compileDmn(t: CustomTable, decisionId: string, name = 'Buying partner custom rules'): string {
  const inputs = t.fields.map((f, i) =>
    `      <input id="in_${i}" label="${esc(f.label || f.key)}"><inputExpression id="ie_${i}" typeRef="${f.type}"><text>${esc(f.expr)}</text></inputExpression></input>`).join('\n');
  const row = (id: string, tests: string[], action: CustomAction, message: string) =>
    `      <rule id="${id}">\n        ${tests.map((x, j) => `<inputEntry id="${id}_${j}"><text>${esc(x.trim() || '-')}</text></inputEntry>`).join('')}\n        <outputEntry id="${id}_o0"><text>${esc(feelString(action))}</text></outputEntry><outputEntry id="${id}_o1"><text>${esc(feelString(message))}</text></outputEntry>\n      </rule>`;
  const rules = t.rules.map((r, i) => row(`r${i + 1}`, t.fields.map((f) => r.tests[f.key] ?? '-'), r.action, r.message || `Rule ${i + 1}`));
  rules.push(row('r_default', t.fields.map(() => '-'), t.defaultAction, 'No rule matched; default outcome.'));
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:camunda="http://camunda.org/schema/1.0/dmn" id="def_${decisionId}" name="${esc(name)}" namespace="https://difme.example/dmn/discountai/custom">
  <decision id="${decisionId}" name="${esc(name)}" camunda:historyTimeToLive="180">
    <decisionTable id="dt_${decisionId}" hitPolicy="FIRST">
${inputs}
      <output id="out_action" name="action" typeRef="string" />
      <output id="out_message" name="message" typeRef="string" />
${rules.join('\n')}
    </decisionTable>
  </decision>
</definitions>
`;
}

export const DEFAULT_CUSTOM: CustomTable = {
  fields: [
    { key: 'amount', label: 'Card amount (₹)', type: 'number', expr: 'amount' },
    { key: 'merchant', label: 'Merchant', type: 'string', expr: 'merchant' },
    { key: 'nightOrder', label: 'Night order (00–06 IST)', type: 'boolean', expr: 'hour < 6' },
    { key: 'discountShare', label: 'Discount left after my yield (%)', type: 'number', expr: 'discountPct - yieldPct * (1 - discountPct / 100)' },
  ],
  rules: [
    { tests: { nightOrder: 'true' }, action: 'REJECT', message: 'No purchases between midnight and 6 am.' },
    { tests: { merchant: 'not("Electronics Store A","Electronics Store B","Marketplace A")' }, action: 'REJECT', message: 'Merchant not on my list.' },
    { tests: { amount: '> 9000' }, action: 'FLAG', message: 'Large order: flag for my weekly review.' },
    { tests: { discountShare: '< 10' }, action: 'REJECT', message: 'Too little of the discount left for the buyer.' },
  ],
  defaultAction: 'APPROVE',
};
