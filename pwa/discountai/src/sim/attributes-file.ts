// Reads an uploaded master list of rule attributes: CSV, JSON or Excel (.xlsx), no extra dependencies.
// Columns (any order, case-insensitive): key (or name/attribute), type (or data type), label (or description), example.
// The exchange validates the list again on save (CustomRules.checkAttributes); the checks here only give quick feedback.

export type AttributeType = 'number' | 'string' | 'boolean';
export interface AttributeRow { key: string; type: string; label?: string; example?: string }
export interface ParsedFile { rows: AttributeRow[]; problems: string[] }

const HEADER: Record<string, keyof AttributeRow> = {
  key: 'key', name: 'key', attribute: 'key', 'attribute name': 'key', field: 'key',
  type: 'type', 'data type': 'type', datatype: 'type', 'data_type': 'type',
  label: 'label', description: 'label', 'display name': 'label',
  example: 'example', sample: 'example', 'example value': 'example',
};
/** Common spellings people use in sheets, mapped to the three types rules support. */
const TYPES: Record<string, AttributeType> = {
  number: 'number', numeric: 'number', integer: 'number', int: 'number', decimal: 'number', float: 'number', double: 'number', amount: 'number',
  string: 'string', text: 'string', varchar: 'string', str: 'string',
  boolean: 'boolean', bool: 'boolean', 'yes/no': 'boolean', flag: 'boolean',
};
const KEY = /^[a-z][A-Za-z0-9_]{0,30}$/;
export const BUILT_IN = ['amount', 'listPrice', 'discountPct', 'yieldPct', 'hour', 'weekday', 'buyerRiskScore'];

export const TEMPLATE_CSV = 'key,type,label,example\n' +
  'merchant,string,Merchant,Electronics Store A\ncategory,string,Goods category,Electronics\nbrand,string,Brand,Brand H\n' +
  'warrantyYears,number,Warranty (years),2\ngiftWrap,boolean,Gift wrapped,false\n';

export async function parseAttributeFile(file: File): Promise<ParsedFile> {
  const name = file.name.toLowerCase();
  if (file.size > 2_000_000) return { rows: [], problems: ['The file is larger than 2 MB.'] };
  try {
    if (name.endsWith('.json')) return fromRecords(fromJson(await file.text()));
    if (name.endsWith('.xlsx')) return fromTable(await readXlsx(await file.arrayBuffer()));
    if (name.endsWith('.csv') || name.endsWith('.txt')) return fromTable(parseCsv(await file.text()));
    if (name.endsWith('.xls')) return { rows: [], problems: ['Old .xls files aren’t supported: save the sheet as .xlsx or .csv.'] };
    return { rows: [], problems: ['Upload a .csv, .xlsx or .json file.'] };
  } catch (e) {
    return { rows: [], problems: [`The file could not be read: ${e instanceof Error ? e.message : String(e)}`] };
  }
}

function fromJson(text: string): Record<string, unknown>[] {
  const j = JSON.parse(text);
  const list = Array.isArray(j) ? j : Array.isArray(j?.attributes) ? j.attributes : null;
  if (!list) throw new Error('expected a list, or {"attributes": [...]}');
  return list;
}

function fromTable(table: string[][]): ParsedFile {
  const nonEmpty = table.filter((r) => r.some((c) => c.trim() !== ''));
  if (nonEmpty.length < 2) return { rows: [], problems: ['The file needs a header row and at least one attribute.'] };
  const cols = nonEmpty[0].map((h) => HEADER[h.trim().toLowerCase()]);
  if (!cols.includes('key') || !cols.includes('type')) return { rows: [], problems: ['The header row needs “key” and “type” columns (also accepted: name, data type).'] };
  return fromRecords(nonEmpty.slice(1).map((r) => {
    const o: Record<string, string> = {};
    cols.forEach((c, i) => { if (c) o[c] = (r[i] ?? '').trim(); });
    return o;
  }));
}

function fromRecords(records: Record<string, unknown>[]): ParsedFile {
  const problems: string[] = [];
  const seen = new Set<string>();
  const rows: AttributeRow[] = [];
  records.forEach((rec, i) => {
    const norm: Record<string, string> = {};
    for (const [k, v] of Object.entries(rec ?? {})) { const c = HEADER[k.trim().toLowerCase()]; if (c && v != null) norm[c] = String(v).trim(); }
    const key = norm.key ?? '';
    const type = TYPES[(norm.type ?? '').toLowerCase()];
    const row = `Row ${i + 1}`;
    if (!KEY.test(key)) problems.push(`${row}: “${key}” must start with a lower-case letter and use only letters, digits or _.`);
    else if (BUILT_IN.includes(key)) problems.push(`${row}: “${key}” is computed by the exchange and can’t be redefined.`);
    else if (seen.has(key)) problems.push(`${row}: “${key}” appears twice.`);
    if (!type) problems.push(`${row}: type “${norm.type ?? ''}” isn’t number, string or boolean.`);
    seen.add(key);
    rows.push({ key, type: type ?? norm.type ?? '', label: norm.label || undefined, example: norm.example || undefined });
  });
  if (rows.length > 200) problems.push('At most 200 attributes.');
  return { rows, problems };
}

/** RFC 4180 CSV: quoted fields, doubled quotes, commas and newlines inside quotes; ; or tab separators too. */
export function parseCsv(text: string): string[][] {
  const t = text.replace(/^﻿/, '');
  const first = t.split(/\r?\n/, 1)[0] ?? '';
  const sep = (first.match(/;/g)?.length ?? 0) > (first.match(/,/g)?.length ?? 0) ? ';' : first.includes('\t') && !first.includes(',') ? '\t' : ',';
  const rows: string[][] = []; let row: string[] = []; let cell = ''; let q = false;
  for (let i = 0; i < t.length; i++) {
    const ch = t[i];
    if (q) {
      if (ch === '"' && t[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') q = false; else cell += ch;
    } else if (ch === '"') q = true;
    else if (ch === sep) { row.push(cell); cell = ''; }
    else if (ch === '\n' || ch === '\r') { if (ch === '\r' && t[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; }
    else cell += ch;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

// ---- minimal .xlsx reader: the first worksheet's cell values (shared strings, inline strings, numbers, booleans) ----

async function readXlsx(buf: ArrayBuffer): Promise<string[][]> {
  const files = await unzip(buf, (n) => n === 'xl/sharedStrings.xml' || n === 'xl/workbook.xml' || n === 'xl/_rels/workbook.xml.rels' || /^xl\/worksheets\/sheet\d+\.xml$/.test(n));
  const xml = (s?: string) => (s ? new DOMParser().parseFromString(s, 'application/xml') : undefined);
  const shared = [...(xml(files['xl/sharedStrings.xml'])?.getElementsByTagName('si') ?? [])].map((si) => [...si.getElementsByTagName('t')].map((t) => t.textContent ?? '').join(''));
  // First sheet in workbook order (its relationship target), else the lowest-numbered sheet file.
  let sheetPath = Object.keys(files).filter((n) => n.startsWith('xl/worksheets/')).sort()[0];
  const wb = xml(files['xl/workbook.xml']); const rels = xml(files['xl/_rels/workbook.xml.rels']);
  const firstRid = wb?.getElementsByTagName('sheet')[0]?.getAttribute('r:id');
  const target = firstRid && [...(rels?.getElementsByTagName('Relationship') ?? [])].find((r) => r.getAttribute('Id') === firstRid)?.getAttribute('Target');
  if (target) sheetPath = 'xl/' + target.replace(/^\/?xl\//, '').replace(/^\//, '');
  const sheet = xml(files[sheetPath]);
  if (!sheet) throw new Error('no worksheet found');
  const out: string[][] = [];
  for (const r of [...sheet.getElementsByTagName('row')]) {
    const cells: string[] = [];
    for (const c of [...r.getElementsByTagName('c')]) {
      const ref = c.getAttribute('r') ?? '';
      const col = ref.replace(/\d+/g, '').split('').reduce((a, ch) => a * 26 + ch.charCodeAt(0) - 64, 0) - 1;
      const t = c.getAttribute('t');
      const v = c.getElementsByTagName('v')[0]?.textContent ?? '';
      const val = t === 's' ? shared[Number(v)] ?? '' : t === 'inlineStr' ? [...c.getElementsByTagName('t')].map((x) => x.textContent ?? '').join('')
        : t === 'b' ? (v === '1' ? 'true' : 'false') : v;
      cells[col >= 0 ? col : cells.length] = val;
    }
    out.push(Array.from(cells, (x) => x ?? ''));
  }
  return out;
}

/** Reads the wanted entries of a zip (stored or deflated) via the browser's DecompressionStream. */
async function unzip(buf: ArrayBuffer, want: (name: string) => boolean): Promise<Record<string, string>> {
  const v = new DataView(buf);
  let eocd = -1;
  for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65_557); i--) if (v.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not an .xlsx (zip) file');
  const count = v.getUint16(eocd + 10, true);
  let p = v.getUint32(eocd + 16, true);
  const dec = new TextDecoder();
  const out: Record<string, string> = {};
  for (let n = 0; n < count; n++) {
    if (v.getUint32(p, true) !== 0x02014b50) throw new Error('damaged zip directory');
    const method = v.getUint16(p + 10, true), size = v.getUint32(p + 20, true);
    const nameLen = v.getUint16(p + 28, true), extraLen = v.getUint16(p + 30, true), commentLen = v.getUint16(p + 32, true);
    const local = v.getUint32(p + 42, true);
    const name = dec.decode(new Uint8Array(buf, p + 46, nameLen));
    p += 46 + nameLen + extraLen + commentLen;
    if (!want(name)) continue;
    const start = local + 30 + v.getUint16(local + 26, true) + v.getUint16(local + 28, true);
    const data = new Uint8Array(buf, start, size);
    if (method === 0) out[name] = dec.decode(data);
    else if (method === 8) out[name] = await new Response(new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).text();
    else throw new Error(`unsupported zip compression ${method}`);
  }
  return out;
}
