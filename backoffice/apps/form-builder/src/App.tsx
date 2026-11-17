// Form Builder (ADR-008): drag-and-drop formio builder from the vendored formio.js, plus user-defined metadata
// that binds the form to its DMN rules (decisionKey + pinned decisionVersion) and the process it starts.
import "./styles.css";
import { useEffect, useRef, useState } from "react";
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { api } from "@difme/auth";
import { Button, Card } from "@difme/ui";

type FormSummary = { path: string; title: string };
type Meta = { decisionKey?: string; decisionVersion?: string; processKey?: string; owner?: string; [k: string]: string | undefined };
type Schema = { title: string; path: string; name: string; type: "form"; display: "form"; components: unknown[]; properties: Meta };

const client = new QueryClient();

function Builder() {
  const forms = useQuery({ queryKey: ["forms"], queryFn: () => api<FormSummary[]>("/api/process/v1/studio/forms") });
  const decisions = useQuery({ queryKey: ["decisions"], queryFn: () => api<{ key: string; version: number }[]>("/api/process/v1/studio/decisions") });
  const [schema, setSchema] = useState<Schema | null>(null);
  const [status, setStatus] = useState("");
  const [extraKey, setExtraKey] = useState("");
  const host = useRef<HTMLDivElement>(null);

  async function open(path: string | null) {
    setSchema(path ? await api<Schema>(`/api/process/v1/studio/forms/${path}`)
      : { title: "New form", path: "new-form", name: "newForm", type: "form", display: "form", components: [], properties: {} });
  }

  useEffect(() => {
    if (!schema || !host.current) return;
    let builder: { on(e: string, cb: (s: { components: unknown[] }) => void): void; destroy(): void } | undefined;
    let live = true;
    (async () => {
      const { Formio } = await import(/* webpackChunkName: "formio-builder" */ "@formio/js");
      await import("@formio/js/dist/formio.builder.min.css");
      if (!live || !host.current) return;
      builder = (await Formio.builder(host.current, { components: schema.components }, {})) as unknown as typeof builder;
      builder!.on("change", (s) => setSchema((cur) => (cur ? { ...cur, components: s.components } : cur)));
    })();
    return () => { live = false; builder?.destroy(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema?.path]);

  async function save() {
    if (!schema) return;
    setStatus("Saving…");
    try {
      await api(`/api/process/v1/studio/forms`, { method: "PUT", body: JSON.stringify(schema) });
      setStatus("Saved");
      forms.refetch();
    } catch (e) {
      setStatus(`Save failed: ${(e as Error).message}`);
    }
  }

  const meta = schema?.properties ?? {};
  const setMeta = (k: string, v: string) => schema && setSchema({ ...schema, properties: { ...meta, [k]: v || undefined } });

  return (
    <div className="grid grid-cols-[14rem_1fr_18rem] gap-4">
      <Card title="Forms" actions={<Button variant="ghost" onClick={() => open(null)}>New</Button>}>
        <ul className="space-y-1 text-sm">
          {(forms.data ?? []).map((f) => (
            <li key={f.path}><button className="w-full rounded px-2 py-1 text-left hover:bg-border/40" onClick={() => open(f.path)}>{f.title}</button></li>
          ))}
        </ul>
      </Card>
      <div>
        {schema && (
          <div className="mb-3 flex items-center gap-2">
            <input className="rounded-md border border-border bg-bg px-2 py-1 text-sm" value={schema.title} onChange={(e) => setSchema({ ...schema, title: e.target.value })} />
            <input className="rounded-md border border-border bg-bg px-2 py-1 font-mono text-sm" value={schema.path}
              onChange={(e) => setSchema({ ...schema, path: e.target.value.replace(/[^a-z0-9-]/g, ""), name: e.target.value.replace(/-./g, (m) => m[1].toUpperCase()) })} />
            <Button onClick={save}>Save</Button><span className="text-xs text-muted">{status}</span>
          </div>
        )}
        <div ref={host} className="rounded-card border border-border bg-white p-2 text-black" />
      </div>
      <Card title="Metadata">
        {!schema ? <p className="text-sm text-muted">Open a form.</p> : (
          <div className="space-y-3 text-sm">
            <label className="flex flex-col"><span className="text-muted">Rules (DMN decision)</span>
              <select className="rounded-md border border-border bg-bg px-2 py-1" value={meta.decisionKey ?? ""} onChange={(e) => setMeta("decisionKey", e.target.value)}>
                <option value="">— none —</option>
                {[...new Set((decisions.data ?? []).map((d) => d.key))].map((k) => <option key={k}>{k}</option>)}
              </select>
            </label>
            <label className="flex flex-col"><span className="text-muted">Pinned decision version</span>
              <select className="rounded-md border border-border bg-bg px-2 py-1" value={meta.decisionVersion ?? ""} onChange={(e) => setMeta("decisionVersion", e.target.value)}>
                <option value="">latest (not recommended)</option>
                {(decisions.data ?? []).filter((d) => d.key === meta.decisionKey).map((d) => <option key={d.version}>{d.version}</option>)}
              </select>
            </label>
            <label className="flex flex-col"><span className="text-muted">Process started on submit</span>
              <input className="rounded-md border border-border bg-bg px-2 py-1 font-mono" value={meta.processKey ?? ""} onChange={(e) => setMeta("processKey", e.target.value)} />
            </label>
            <label className="flex flex-col"><span className="text-muted">Owner</span>
              <input className="rounded-md border border-border bg-bg px-2 py-1" value={meta.owner ?? ""} onChange={(e) => setMeta("owner", e.target.value)} />
            </label>
            <div className="border-t border-border pt-3">
              <p className="mb-2 text-muted">Custom metadata</p>
              {Object.entries(meta).filter(([k]) => !["decisionKey", "decisionVersion", "processKey", "owner"].includes(k)).map(([k, v]) => (
                <label key={k} className="mb-2 flex flex-col"><span className="font-mono text-xs text-muted">{k}</span>
                  <input className="rounded-md border border-border bg-bg px-2 py-1" value={v ?? ""} onChange={(e) => setMeta(k, e.target.value)} /></label>
              ))}
              <div className="flex gap-2">
                <input placeholder="key" className="w-full rounded-md border border-border bg-bg px-2 py-1 font-mono" value={extraKey} onChange={(e) => setExtraKey(e.target.value.replace(/[^A-Za-z0-9_]/g, ""))} />
                <Button variant="ghost" disabled={!extraKey} onClick={() => { setMeta(extraKey, " "); setExtraKey(""); }}>Add</Button>
              </div>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}

export default function App() {
  return <QueryClientProvider client={client}><Builder /></QueryClientProvider>;
}
