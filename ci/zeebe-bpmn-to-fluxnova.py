#!/usr/bin/env python3
"""Converts Mifos Payment Hub EE BPMN flows (Zeebe dialect) to Fluxnova, leaving the vendored files untouched.

  ci/zeebe-bpmn-to-fluxnova.py <in-dir> <out-dir> [--tenant NAME] [--only FILE ...] [--skip FILE ...]
  (bill-request-DFSPID.bpmn is an incomplete upstream draft, service tasks without job types: skip it)

What changes (owner decision 2026-11-21: PH-EE on Fluxnova instead of Zeebe):
  zeebe:taskDefinition type="t"         -> fluxnova:type="external" fluxnova:topic="t"   (the worker's job type)
  zeebe:subscription correlationKey     -> removed; message name -> correlation variable(s) written to
                                           zeebe-on-fluxnova-messages.properties (read by services/zeebe-on-fluxnova;
                                           several when flows correlate one message on different variables)
  zeebe:calledElement processId="p"     -> calledElement="p" + fluxnova:in/out variables="all"
  conditionExpression "=expr" (FEEL)    -> language="feel" expr   (Fluxnova evaluates FEEL as a script language)
  timer "= var" / "=expr"               -> ${var} (simple variable names only; anything else fails the conversion)
  every process                         -> fluxnova:historyTimeToLive (Fluxnova requires one)
  --tenant NAME                         -> "DFSPID" replaced by NAME, as PH-EE's own deploy script does
Anything Zeebe-specific it doesn't know makes it stop, so nothing is silently lost.
"""
import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
ZEEBE = "http://camunda.org/schema/zeebe/1.0"
FLUX = "http://fluxnova.finos.org/schema/1.0/bpmn"
XSI = "http://www.w3.org/2001/XMLSchema-instance"
MODELER = "http://camunda.org/schema/modeler/1.0"
for prefix, uri in {"bpmn": BPMN, "bpmndi": "http://www.omg.org/spec/BPMN/20100524/DI",
                    "dc": "http://www.omg.org/spec/DD/20100524/DC", "di": "http://www.omg.org/spec/DD/20100524/DI",
                    "xsi": XSI, "fluxnova": FLUX, "modeler": MODELER}.items():
    ET.register_namespace(prefix, uri)

SIMPLE_VAR = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
HISTORY_TTL = "P30D"


def q(ns, tag):
    return f"{{{ns}}}{tag}"


def feel(text):
    """'= expr' -> 'expr' (a Zeebe expression); None when the text is not an expression."""
    t = (text or "").strip()
    return t[1:].strip() if t.startswith("=") else None


def convert(path: pathlib.Path, tenant: str | None, messages: dict, problems: list):
    raw = path.read_text(encoding="utf-8")
    if tenant:
        raw = raw.replace("DFSPID", tenant)
    root = ET.fromstring(raw)
    parents = {c: p for p in root.iter() for c in p}

    for proc in root.iter(q(BPMN, "process")):
        proc.set("isExecutable", "true")
        proc.set(q(FLUX, "historyTimeToLive"), HISTORY_TTL)

    for ext in list(root.iter(q(BPMN, "extensionElements"))):
        owner = parents[ext]
        for z in list(ext):
            if not z.tag.startswith(f"{{{ZEEBE}}}"):
                continue
            kind = z.tag.split("}")[1]
            if kind == "taskDefinition":
                topic = z.get("type", "")
                if topic.startswith("="):
                    problems.append(f"{path.name}: task {owner.get('id')} has an expression job type {topic}")
                owner.set(q(FLUX, "type"), "external")
                owner.set(q(FLUX, "topic"), topic)
            elif kind == "subscription":
                key = feel(z.get("correlationKey"))
                name = owner.get("name")
                if key is None or not SIMPLE_VAR.match(key):
                    problems.append(f"{path.name}: message {name} correlates on {z.get('correlationKey')!r} (not a variable)")
                else:
                    # Flows may correlate the same message on different variables: keep every candidate.
                    messages.setdefault(name, [])
                    if key not in messages[name]:
                        messages[name].append(key)
            elif kind == "calledElement":
                owner.set("calledElement", z.get("processId"))
                if z.get("propagateAllChildVariables", "true") != "false":
                    ET.SubElement(ext, q(FLUX, "out"), {"variables": "all"})
                ET.SubElement(ext, q(FLUX, "in"), {"variables": "all"})
            else:
                problems.append(f"{path.name}: unsupported zeebe:{kind} on {owner.get('id')}")
            ext.remove(z)
        if len(ext) == 0:
            owner.remove(ext)

    for cond in root.iter(q(BPMN, "conditionExpression")):
        expr = feel(cond.text)
        if expr is not None:
            cond.text = expr
            cond.set("language", "feel")

    for tag in ("timeDuration", "timeCycle", "timeDate"):
        for t in root.iter(q(BPMN, tag)):
            expr = feel(t.text)
            if expr is None:
                continue
            if SIMPLE_VAR.match(expr):
                t.text = "${" + expr + "}"
            else:
                problems.append(f"{path.name}: timer expression {expr!r} is not a variable")

    for task in root.iter(q(BPMN, "serviceTask")):
        if task.get(q(FLUX, "topic")) is None:
            problems.append(f"{path.name}: service task {task.get('id')} ({task.get('name')}) has no job type (incomplete upstream flow)")

    leftover = [e.tag for e in root.iter() if e.tag.startswith(f"{{{ZEEBE}}}")]
    leftover += [f"@{k}" for e in root.iter() for k in e.attrib if k.startswith(f"{{{ZEEBE}}}")]
    if leftover:
        problems.append(f"{path.name}: Zeebe constructs left: {sorted(set(leftover))}")
    return ET.tostring(root, encoding="unicode", xml_declaration=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--tenant")
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--skip", nargs="*", default=[], help="upstream flows known to be incomplete")
    a = ap.parse_args()
    src, out = pathlib.Path(a.src), pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    files = sorted(src.glob("*.bpmn")) if not a.only else [src / f for f in a.only]
    files = [f for f in files if f.name not in a.skip]
    messages, problems, written = {}, [], 0
    for f in files:
        converted = convert(f, a.tenant, messages, problems)
        name = f.name.replace("DFSPID", a.tenant) if a.tenant else f.name
        (out / name).write_text(converted, encoding="utf-8")
        written += 1
    (out / "zeebe-on-fluxnova-messages.properties").write_text(
        "# message name = process variable its correlation key matches (from zeebe:subscription)\n"
        + "".join(f"{k}={','.join(v)}\n" for k, v in sorted(messages.items())), encoding="utf-8")
    for p in problems:
        print("PROBLEM:", p, file=sys.stderr)
    print(f"converted {written} flows, {len(messages)} messages, {len(problems)} problems")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
