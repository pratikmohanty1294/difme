import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@difme/auth";
import { Button, Card } from "@difme/ui";
import { apply, EDITABLE, elements, extract, type Sidecar } from "./fluxnovaProps";

type Model = { type: "bpmn" | "dmn"; key: string; name: string; version: number };
type Editor = { getContent(): Promise<string>; close(): void; subscribeToContentChanges(cb: (dirty: boolean) => void): unknown };

export default function Modeler() {
  const models = useQuery({ queryKey: ["models"], queryFn: () => api<Model[]>("/api/process/v1/studio/models") });
  const [current, setCurrent] = useState<Model | null>(null);
  const [newKey, setNewKey] = useState("");
  const [xml, setXml] = useState("");
  const [sidecar, setSidecar] = useState<Sidecar>({});
  const [selected, setSelected] = useState<string>("");
  const [status, setStatus] = useState("");
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<Editor | null>(null);

  async function open(m: Model | null, type: "bpmn" | "dmn" = "bpmn", key = "") {
    const content = m ? (await api<{ xml: string }>(`/api/process/v1/studio/models/${m.type}/${m.key}`)).xml : "";
    setCurrent(m ?? { type, key, name: key, version: 0 });
    setXml(content);
    setSidecar(m ? extract(content) : {});
  }

  useEffect(() => {
    if (!current || !host.current) return;
    let cancelled = false;
    const load = current.type === "bpmn"
      ? import("@kie-tools/bpmn-editor-standalone/dist")
      : import("@kie-tools/dmn-editor-standalone/dist");
    load.then((Kie) => {
      if (cancelled || !host.current) return;
      host.current.innerHTML = "";
      editor.current = (Kie as unknown as { open(o: object): Editor }).open({
        container: host.current,
        initialContent: Promise.resolve(xml),
        initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: `${current.key}.${current.type}`,
        readOnly: false,
      });
      editor.current.subscribeToContentChanges(async () => setXml((await editor.current?.getContent()) ?? ""));
    });
    return () => { cancelled = true; editor.current?.close(); editor.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.type, current?.key]);

  async function deploy() {
    if (!current || !editor.current) return;
    const content = await editor.current.getContent();
    const finalXml = current.type === "bpmn" ? apply(content, sidecar, current.key) : content;
    setStatus("Deploying…");
    try {
      const r = await api<{ id: string }>("/api/process/v1/studio/deploy", {
        method: "POST", body: JSON.stringify({ name: current.key, files: [{ name: `${current.key}.${current.type}`, content: finalXml }] }),
      });
      setStatus(`Deployed (${r.id})`);
      models.refetch();
    } catch (e) {
      setStatus(`Deploy failed: ${(e as Error & { body?: string }).body ?? (e as Error).message}`);
    }
  }

  const els = current?.type === "bpmn" && xml ? elements(xml) : [];
  const props = sidecar[selected] ?? {};

  return (
    <div className="grid grid-cols-[16rem_1fr_18rem] gap-4">
      <Card title="Models">
        <ul className="space-y-1 text-sm">
          {(models.data ?? []).map((m) => (
            <li key={m.type + m.key}>
              <button className="w-full rounded px-2 py-1 text-left hover:bg-border/40" onClick={() => open(m)}>
                <span className="mr-2 rounded bg-border px-1 text-xs uppercase">{m.type}</span>{m.name || m.key} <span className="text-muted">v{m.version}</span>
              </button>
            </li>
          ))}
        </ul>
        <div className="mt-4 space-y-2">
          <input placeholder="new-model-key" value={newKey} onChange={(e) => setNewKey(e.target.value.replace(/[^a-z0-9-]/g, ""))}
            className="w-full rounded-md border border-border bg-bg px-2 py-1 text-sm" />
          <div className="flex gap-2">
            <Button variant="ghost" disabled={!newKey} onClick={() => open(null, "bpmn", newKey)}>New BPMN</Button>
            <Button variant="ghost" disabled={!newKey} onClick={() => open(null, "dmn", newKey)}>New DMN</Button>
          </div>
        </div>
      </Card>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted">{current ? `${current.type.toUpperCase()} · ${current.key}` : "Open or create a model"}</span>
          <div className="flex items-center gap-3"><span className="text-xs text-muted">{status}</span><Button disabled={!current} onClick={deploy}>Deploy to Fluxnova</Button></div>
        </div>
        <div ref={host} className="h-[70vh] rounded-card border border-border bg-white" />
      </div>

      <Card title="Fluxnova properties">
        {current?.type !== "bpmn" ? <p className="text-sm text-muted">DMN tables need no extra execution properties.</p> : (
          <div className="space-y-3 text-sm">
            <select className="w-full rounded-md border border-border bg-bg px-2 py-1" value={selected} onChange={(e) => setSelected(e.target.value)}>
              <option value="">Select an element…</option>
              {els.map((e) => <option key={e.id} value={e.id}>{e.kind} · {e.name || e.id}</option>)}
            </select>
            {selected && EDITABLE.map((k) => (
              <label key={k} className="flex flex-col">
                <span className="text-muted">{k}</span>
                <input className="rounded-md border border-border bg-bg px-2 py-1 font-mono text-xs" value={props[k] ?? ""}
                  placeholder={k === "topic" ? "temporal:WorkflowType" : k === "formKey" ? "formio:form-path" : ""}
                  onChange={(e) => setSidecar({ ...sidecar, [selected]: { ...props, [k]: e.target.value } })} />
              </label>
            ))}
            <p className="text-xs text-muted">Service tasks that call other systems use type <code>external</code> and topic <code>temporal:&lt;WorkflowType&gt;</code> — inter-process orchestration stays in Temporal.</p>
          </div>
        )}
      </Card>
    </div>
  );
}
