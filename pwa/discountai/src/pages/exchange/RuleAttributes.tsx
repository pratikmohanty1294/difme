import { useEffect, useState } from 'react';
import { Download, FileUp, Save } from 'lucide-react';
import { LIVE, exchange, ExchangeError, type AttributeCatalog } from '../../api/exchange';
import { parseAttributeFile, TEMPLATE_CSV, type ParsedFile } from '../../sim/attributes-file';
import { Badge, Callout, Card, PageHeader } from '../../components/ui';

/**
 * Exchange operator: the vertical's master list of order attributes. Buying partners' custom rules may use these (plus
 * the built-ins the exchange computes), and buyers' agents send their values with each intent. Upload a CSV, Excel or
 * JSON file, check the preview, save: the exchange validates it again and refuses a list that would break active rules.
 */
export default function RuleAttributes() {
  const [catalog, setCatalog] = useState<AttributeCatalog>();
  const [loadError, setLoadError] = useState<string>();
  const [file, setFile] = useState<string>();
  const [parsed, setParsed] = useState<ParsedFile>();
  const [saving, setSaving] = useState(false);
  const [saveResult, setSaveResult] = useState<{ ok: boolean; text: string; problems?: string[] }>();

  const load = () => exchange.attributes().then((c) => { setCatalog(c); setLoadError(undefined); },
    (e) => setLoadError(e instanceof ExchangeError && e.status === 0 ? 'The exchange is unreachable.' : String(e instanceof Error ? e.message : e)));
  useEffect(() => { if (LIVE) load(); }, []);

  const onFile = async (f: File | undefined) => {
    setSaveResult(undefined);
    if (!f) return;
    setFile(f.name);
    setParsed(await parseAttributeFile(f));
  };

  const save = async () => {
    if (!parsed || parsed.problems.length) return;
    setSaving(true);
    try {
      const c = await exchange.replaceAttributes(parsed.rows);
      setCatalog(c);
      setSaveResult({ ok: true, text: `Saved: ${c.attributes.length} attributes are now available to rule expressions.` });
      setParsed(undefined); setFile(undefined);
    } catch (e) {
      const body = e instanceof ExchangeError ? (e.body as { problems?: string[]; error?: string } | undefined) : undefined;
      setSaveResult({ ok: false, text: body?.error ?? (e instanceof Error ? e.message : String(e)), problems: body?.problems });
    } finally {
      setSaving(false);
    }
  };

  const template = `data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE_CSV)}`;

  return (
    <>
      <PageHeader eyebrow="Exchange OME · rules" title="Rule attributes"
        desc="The master list of order attributes buying partners can use in their custom rule expressions. Buyers' agents send these values with each request; the exchange checks every value against the type here." />
      {!LIVE && <Callout tone="info" title="Live builds only">The attribute catalog lives on the exchange. Open the Exchange OME app connected to an exchange to manage it.</Callout>}
      {loadError && <Callout tone="warn" title="Couldn't load the catalog">{loadError}</Callout>}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <Card title="Upload a master list" sub="CSV, Excel (.xlsx) or JSON · columns: key, type, label, example">
          <label className="flex flex-col items-start gap-2 text-sm">
            <span className="font-medium text-ink-900">File</span>
            <input aria-label="Attribute file" type="file" accept=".csv,.xlsx,.json,.txt" onChange={(e) => onFile(e.target.files?.[0])} />
          </label>
          <p className="mt-2 text-xs text-ink-600">
            Type is number, string or boolean (common spellings like integer, text or yes/no are accepted). Names start with a
            lower-case letter and use letters, digits or _. Saving replaces the whole list.
          </p>
          <a className="btn-ghost mt-2 inline-flex text-xs" href={template} download="rule-attributes-template.csv"><Download size={14} /> Download a template</a>

          {parsed && (
            <div className="mt-4">
              <p className="text-sm font-medium">{file} · {parsed.rows.length} attributes</p>
              {parsed.problems.length > 0
                ? <Callout tone="warn" title="Fix these in the file, then upload it again"><ul className="list-disc pl-5">{parsed.problems.slice(0, 20).map((p) => <li key={p}>{p}</li>)}</ul></Callout>
                : <table className="mt-2 w-full text-sm"><thead><tr><th className="th">Key</th><th className="th">Type</th><th className="th">Label</th><th className="th">Example</th></tr></thead>
                    <tbody>{parsed.rows.map((r) => <tr key={r.key}><td className="td font-mono">{r.key}</td><td className="td">{r.type}</td><td className="td">{r.label}</td><td className="td">{r.example}</td></tr>)}</tbody></table>}
              <button className="btn-primary mt-3" disabled={saving || parsed.problems.length > 0 || !LIVE} onClick={save}>
                {saving ? 'Saving…' : <><Save size={15} /> Save to the exchange</>}
              </button>
            </div>
          )}
          {saveResult && (saveResult.ok
            ? <div className="mt-3"><Callout tone="good" title="Saved">{saveResult.text}</Callout></div>
            : <div className="mt-3"><Callout tone="warn" title="Not saved">{saveResult.text}{saveResult.problems && <ul className="list-disc pl-5 mt-1">{saveResult.problems.slice(0, 20).map((p) => <li key={p}>{p}</li>)}</ul>}</Callout></div>)}
        </Card>

        <Card title="Current catalog" sub={catalog ? `${catalog.pack} · ${catalog.attributes.length} order attributes + ${catalog.builtIn.length} built-in` : '—'}>
          {catalog && (
            <table className="w-full text-sm">
              <thead><tr><th className="th">Key</th><th className="th">Type</th><th className="th">Label</th><th className="th">Source</th></tr></thead>
              <tbody>
                {catalog.builtIn.map((b) => <tr key={b.key}><td className="td font-mono">{b.key}</td><td className="td">{b.type}</td><td className="td text-ink-500">computed per bid</td><td className="td"><Badge>built-in</Badge></td></tr>)}
                {catalog.attributes.map((a) => <tr key={a.key}><td className="td font-mono">{a.key}</td><td className="td">{a.type}</td><td className="td">{a.label ?? ''}</td><td className="td"><Badge tone="blue">order</Badge></td></tr>)}
              </tbody>
            </table>
          )}
          {!catalog && !loadError && LIVE && <p className="text-sm text-ink-600"><FileUp size={14} className="inline" /> Loading…</p>}
        </Card>
      </div>
    </>
  );
}
