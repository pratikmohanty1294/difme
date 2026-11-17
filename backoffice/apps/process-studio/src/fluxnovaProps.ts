// Fluxnova execution properties are kept in a sidecar and (re)applied to the editor's XML on every deploy, so they
// survive whatever the BPMN editor does with extension attributes.
export const FLUXNOVA_NS = "http://fluxnova.finos.org/schema/1.0/bpmn";
export const EDITABLE = ["assignee", "candidateGroups", "formKey", "decisionRef", "resultVariable", "mapDecisionResult", "type", "topic"] as const;
export type Props = Partial<Record<(typeof EDITABLE)[number], string>>;
export type Sidecar = Record<string, Props>;   // element id → properties

const TASKS = ["userTask", "serviceTask", "businessRuleTask", "startEvent", "sendTask", "scriptTask"];

export function elements(xml: string): { id: string; kind: string; name: string }[] {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  return TASKS.flatMap((t) => Array.from(doc.getElementsByTagNameNS("*", t)))
    .map((el) => ({ id: el.getAttribute("id") ?? "", kind: el.localName, name: el.getAttribute("name") ?? "" }))
    .filter((e) => e.id);
}

export function extract(xml: string): Sidecar {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  const out: Sidecar = {};
  doc.querySelectorAll("[id]").forEach((el) => {
    const p: Props = {};
    for (const k of EDITABLE) {
      const v = el.getAttributeNS(FLUXNOVA_NS, k);
      if (v) p[k] = v;
    }
    if (Object.keys(p).length) out[el.getAttribute("id")!] = p;
  });
  return out;
}

export function apply(xml: string, sidecar: Sidecar, processId: string): string {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  const root = doc.documentElement;
  if (!root.lookupPrefix(FLUXNOVA_NS)) root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:fluxnova", FLUXNOVA_NS);
  const proc = Array.from(doc.getElementsByTagNameNS("*", "process"))[0];
  if (proc) {
    proc.setAttribute("isExecutable", "true");
    if (!proc.getAttribute("id")) proc.setAttribute("id", processId);
    proc.setAttributeNS(FLUXNOVA_NS, "fluxnova:historyTimeToLive", "P30D");
  }
  for (const [id, props] of Object.entries(sidecar)) {
    const el = doc.querySelector(`[id="${CSS.escape(id)}"]`);
    if (!el) continue;
    for (const [k, v] of Object.entries(props)) {
      if (v) el.setAttributeNS(FLUXNOVA_NS, `fluxnova:${k}`, v);
      else el.removeAttributeNS(FLUXNOVA_NS, k);
    }
  }
  return new XMLSerializer().serializeToString(doc);
}
