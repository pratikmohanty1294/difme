#!/usr/bin/env python3
"""Licence scan of every third-party package DIFME builds or ships (owner rule: free and open source only).

  ci/licence-scan.py [--report docs/knowledge-base/licence-scan.md]

Ecosystems: npm (installed node_modules), Cargo (cargo metadata, offline), Go (module cache LICENSE files), Maven /
Gradle (POM <licenses> of every jar in ~/.m2 and Gradle's cache that our builds resolved), Python (installed dists).
Each package's declared licence (an SPDX expression; OR = any acceptable branch suffices, AND = all must be) is put in:
  ok       OSI-approved
  review   free but not OSI-approved (public-domain dedications, data licences): looked at by a person
  deny     not open source (BSL/BUSL, SSPL, Elastic, Commons Clause, JSON licence, "All Rights Reserved", proprietary)
  unknown  nothing declared: looked at by a person
Exit code 1 when anything is in deny.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent

OSI = {s.lower() for s in """
Apache-2.0 Apache-1.1 MIT MIT-0 X11 BSD-2-Clause BSD-3-Clause BSD-1-Clause 0BSD ISC MPL-1.1 MPL-2.0 MPL-2.0-no-copyleft-exception
EPL-1.0 EPL-2.0 LGPL-2.0 LGPL-2.0-only LGPL-2.0-or-later LGPL-2.1 LGPL-2.1-only LGPL-2.1-or-later LGPL-3.0 LGPL-3.0-only
LGPL-3.0-or-later GPL-2.0 GPL-2.0-only GPL-2.0-or-later GPL-3.0 GPL-3.0-only GPL-3.0-or-later AGPL-3.0 AGPL-3.0-only
AGPL-3.0-or-later GPL-2.0-with-classpath-exception CDDL-1.0 CDDL-1.1 Unlicense Zlib BSL-1.0 PostgreSQL Python-2.0
PSF-2.0 Unicode-DFS-2016 Unicode-3.0 Artistic-2.0 MS-PL MS-RL EUPL-1.1 EUPL-1.2 BlueOak-1.0.0 UPL-1.0 NCSA OFL-1.1
W3C W3C-20150513 CPL-1.0 CC-BY-4.0 AFL-2.1 AFL-3.0 OSL-3.0 MulanPSL-2.0 LiLiQ-P-1.1 CECILL-2.1 ECL-2.0 Python-2.0.1 HPND
""".split()}
REVIEW = {s.lower() for s in "CC0-1.0 CC-BY-3.0 CC-BY-SA-4.0 Public-Domain WTFPL CDLA-Permissive-2.0 CDLA-Permissive-1.0 OpenSSL SSLeay bzip2-1.0.6 libpng-2.0 Ruby".split()}
DENY_PAT = re.compile(r"BUSL|Business Source|SSPL|Server Side Public|Elastic|Commons.?Clause|\bJSON\b|All Rights Reserved|"
                      r"proprietary|UNLICENSED|Confluent Community|Camunda License|RSAL|Redis Source Available|FSL-|"
                      r"Functional Source|Polyform|prohibited", re.I)

# Common free-text licence names -> SPDX (POMs and old package.json files rarely use SPDX).
ALIASES = [
    (r"apache.*2", "Apache-2.0"), (r"\bmit\b|mit license|the mit", "MIT"), (r"bsd.*3|new bsd|revised bsd|bsd-3", "BSD-3-Clause"),
    (r"bsd.*2|simplified bsd|freebsd", "BSD-2-Clause"), (r"^bsd$|bsd license|\bbsd\b", "BSD-3-Clause"),
    (r"eclipse public license.*2|epl.?2", "EPL-2.0"), (r"eclipse public license|epl.?1", "EPL-1.0"),
    (r"eclipse distribution license|edl", "BSD-3-Clause"),
    (r"mozilla public license.*2|mpl.?2", "MPL-2.0"), (r"lesser general public license.*3|lgpl.*3", "LGPL-3.0"),
    (r"lesser general public license|lgpl", "LGPL-2.1"), (r"affero", "AGPL-3.0"),
    (r"gpl.*classpath|classpath exception|gplv2\+ce", "GPL-2.0-with-classpath-exception"),
    (r"general public license.*3|gpl.*3", "GPL-3.0"), (r"general public license|gpl", "GPL-2.0"),
    (r"cddl", "CDDL-1.1"), (r"common public license|\\bcpl\\b", "CPL-1.0"), (r"public domain", "Public-Domain"), (r"cc0", "CC0-1.0"), (r"isc", "ISC"),
    (r"bouncy castle", "MIT"), (r"postgresql", "PostgreSQL"), (r"unicode", "Unicode-DFS-2016"), (r"zlib", "Zlib"),
    (r"boost", "BSL-1.0"), (r"\bupl\b|universal permissive", "UPL-1.0"), (r"go license", "BSD-3-Clause"),
    (r"psf|python software foundation", "PSF-2.0"), (r"unlicense", "Unlicense"), (r"0bsd", "0BSD"),
]


def normalise(name):
    n = (name or "").strip()
    if not n:
        return ""
    if n.lower() in OSI or n.lower() in REVIEW:
        return n
    low = n.lower()
    for pat, spdx in ALIASES:
        if re.search(pat, low):
            return spdx
    return n


def classify(expr):
    """Classifies an SPDX-ish expression; returns (class, normalised text)."""
    if not expr or not str(expr).strip():
        return "unknown", ""
    e = str(expr).strip()
    if DENY_PAT.search(e) and not re.search(r"\bOR\b", e):
        return "deny", e
    e2 = e.replace("(", " ").replace(")", " ")
    alternatives = [a for a in re.split(r"\s+OR\s+|\s*/\s*", e2) if a.strip()]
    best = "deny"
    order = {"ok": 0, "review": 1, "unknown": 2, "deny": 3}
    for alt in alternatives:
        parts = [p.strip() for p in re.split(r"\s+AND\s+", alt) if p.strip()]
        worst = "ok"
        for p in parts:
            p = re.sub(r"\s+WITH\s+.*", "", p)
            n = normalise(p).lower()
            c = "ok" if n in OSI else "review" if n in REVIEW else "deny" if DENY_PAT.search(p) else "unknown"
            if order[c] > order[worst]:
                worst = c
        if order[worst] < order[best]:
            best = worst
    return best, e


# ── ecosystems ─────────────────────────────────────────────────────────────────────────────────────────────────────

def npm_packages():
    out = {}
    for nm in ROOT.rglob("node_modules"):
        if "node_modules" in nm.parent.parts:
            continue
        for pj in nm.rglob("package.json"):
            rel = pj.relative_to(nm).parts
            if len(rel) not in (2, 3) or (len(rel) == 3 and not rel[0].startswith("@")):
                continue
            try:
                d = json.loads(pj.read_text(errors="replace"))
            except Exception:
                continue
            if not isinstance(d, dict) or "name" not in d:
                continue
            lic = d.get("license") or d.get("licenses")
            if isinstance(lic, dict):
                lic = lic.get("type")
            if isinstance(lic, list):
                lic = " OR ".join((x.get("type") if isinstance(x, dict) else str(x)) for x in lic)
            key = f"npm:{d['name']}@{d.get('version','?')}"
            out.setdefault(key, (lic or "", str(nm.parent.relative_to(ROOT))))
    return out


def npm_lockfiles():
    """package-lock.json (v2/v3) records each package's licence: covers projects whose node_modules isn't installed."""
    out = {}
    for lock in ROOT.rglob("package-lock.json"):
        if "node_modules" in lock.parts:
            continue
        try:
            d = json.loads(lock.read_text())
        except Exception:
            continue
        for path, meta in (d.get("packages") or {}).items():
            if not path or meta.get("link") or meta.get("dev"):
                continue   # the project itself, workspace links, and dev-only tools (not shipped)
            name = meta.get("name") or path.split("node_modules/")[-1]
            out.setdefault(f"npm:{name}@{meta.get('version','?')}", (meta.get("license") or "", str(lock.parent.relative_to(ROOT))))
    return out


def cargo_packages():
    out = {}
    for lock in [ROOT / "vendor/hyperswitch/Cargo.lock"] + list(ROOT.glob("services/*/Cargo.lock")) + list(ROOT.glob("edge/*/Cargo.lock")):
        if not lock.exists():
            continue
        try:
            meta = json.loads(subprocess.run(["cargo", "metadata", "--format-version", "1", "--offline", "--locked"],
                                             cwd=lock.parent, capture_output=True, text=True, timeout=600).stdout)
        except Exception as e:
            out[f"cargo:{lock.parent.name}:metadata-failed"] = ("", str(e))
            continue
        for p in meta.get("packages", []):
            if p.get("source") is None:
                continue   # workspace members (the project itself)
            out[f"cargo:{p['name']}@{p['version']}"] = (p.get("license") or (f"file:{p.get('license_file')}" if p.get("license_file") else ""),
                                                       str(lock.parent.relative_to(ROOT)))
    return out


def go_packages():
    out = {}
    for mod in ROOT.glob("services/*/go.mod"):
        r = subprocess.run(["go", "list", "-m", "-json", "all"], cwd=mod.parent, capture_output=True, text=True,
                           env={**__import__("os").environ, "GOTOOLCHAIN": "auto", "GOFLAGS": "-mod=mod"}, timeout=600)
        dec = json.JSONDecoder()
        s, i = r.stdout, 0
        while i < len(s):
            s2 = s[i:].lstrip()
            if not s2:
                break
            obj, n = dec.raw_decode(s2)
            i = len(s) - len(s2) + n
            if obj.get("Main"):
                continue
            d = obj.get("Dir") or ""
            lic = ""
            for name in ("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING", "LICENCE"):
                f = pathlib.Path(d) / name
                if d and f.exists():
                    t = f.read_text(errors="replace")[:3000]
                    lic = guess_licence_text(t)
                    break
            out[f"go:{obj['Path']}@{obj.get('Version','')}"] = (lic, str(mod.parent.relative_to(ROOT)))
    return out


def guess_licence_text(t):
    low = t.lower()
    if "apache license" in low and "version 2.0" in low:
        return "Apache-2.0"
    if "mozilla public license" in low and "2.0" in low:
        return "MPL-2.0"
    if "permission is hereby granted, free of charge" in low:
        return "MIT"
    if "redistribution and use in source and binary forms" in low:
        return "BSD-3-Clause" if "neither the name" in low or "names of its" in low else "BSD-2-Clause"
    if "permission to use, copy, modify, and/or distribute" in low:
        return "ISC"
    if "gnu lesser general public license" in low:
        return "LGPL-3.0"
    if "gnu general public license" in low:
        return "GPL-3.0" if "version 3" in low else "GPL-2.0"
    if "all rights reserved" in low and ("prohibited" in low or "without permission" in low):
        return "All Rights Reserved"
    return t.strip().splitlines()[0][:80] if t.strip() else ""


POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def pom_licence(pom, depth=0):
    try:
        root = ET.parse(pom).getroot()
    except Exception:
        return ""
    names = [l.findtext(f"{POM_NS}name") or l.findtext("name") for l in root.iter(f"{POM_NS}license")] or \
            [l.findtext("name") for l in root.iter("license")]
    names = [n for n in names if n]
    if names:
        return " OR ".join(sorted(set(names))) if len(names) > 1 else names[0]
    parent = root.find(f"{POM_NS}parent")
    if parent is not None and depth < 6:
        g, a, v = (parent.findtext(f"{POM_NS}{t}") for t in ("groupId", "artifactId", "version"))
        for base in pom_roots():
            cand = base / g.replace(".", "/") / a / v / f"{a}-{v}.pom"
            if cand.exists():
                return pom_licence(cand, depth + 1)
        for cand in (pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1" / g / a / v).glob("*/*.pom"):
            return pom_licence(cand, depth + 1)
    return ""


def pom_roots():
    return [pathlib.Path.home() / ".m2/repository"]


def jvm_packages():
    """Every jar our JVM builds put in a runnable artifact (boot jars, Quarkus app libs, Fineract jar)."""
    out = {}
    jars = []
    extra = [pathlib.Path(x) for x in __import__("os").environ.get("LICENCE_SCAN_JARS", "").split(":") if x]
    for bj in list(ROOT.glob("vendor/mifos-ph-ee/*/build/libs/*.jar")) + list(ROOT.glob("vendor/fineract/fineract-provider/build/libs/*.jar")) + extra:
        r = subprocess.run(["unzip", "-l", str(bj)], capture_output=True, text=True)
        where = str(bj.relative_to(ROOT)) if bj.is_relative_to(ROOT) else bj.name
        jars += [(pathlib.Path(l.split()[-1]).name, where) for l in r.stdout.splitlines() if l.strip().endswith(".jar") and "/lib/" in l]
    for lib in ROOT.glob("services/core/*/target/quarkus-app/lib/main"):
        jars += [(j.name, str(lib.relative_to(ROOT))) for j in lib.glob("*.jar")]
    # Index the local caches by jar file name.
    index = {}
    for j in (pathlib.Path.home() / ".m2/repository").rglob("*.jar"):
        index.setdefault(j.name, j.with_suffix(".pom"))
    for j in (pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1").rglob("*.jar"):
        poms = list(j.parent.parent.glob("*/*.pom"))
        index.setdefault(j.name, poms[0] if poms else None)
    for name, where in jars:
        if name.startswith(("ph-ee-connector", "fineract-", "difme-", "zeebe-client-fluxnova", "fspiop-ilp-models")):
            continue   # our own / vendored-and-reviewed sources
        jname = re.sub(r"^\d+-", "", name)
        pom = index.get(jname)
        # Quarkus prefixes jar names with the groupId: group.artifact-version.jar
        if pom is None and "." in jname:
            pom = next((index[k] for k in index if jname.endswith(k)), None)
        lic = pom_licence(pom) if pom and pom.exists() else ""
        out[f"jar:{jname}"] = (lic, where)
    return out


def python_packages():
    out = {}
    r = subprocess.run([sys.executable, "-m", "pip", "list", "--format", "json"], capture_output=True, text=True)
    try:
        pkgs = json.loads(r.stdout)
    except Exception:
        return out
    reqs = set()
    for req in ROOT.rglob("requirements*.txt"):
        if "node_modules" in req.parts or "vendor" in req.parts:
            continue
        for line in req.read_text().splitlines():
            m = re.match(r"\s*([A-Za-z0-9_.\-]+)", line)
            if m and not line.strip().startswith("#"):
                reqs.add(m.group(1).lower().replace("_", "-"))
    from importlib import metadata
    for p in pkgs:
        if p["name"].lower().replace("_", "-") not in reqs:
            continue
        try:
            md = metadata.metadata(p["name"])
            lic = md.get("License-Expression") or md.get("License") or ""
            if not lic or len(lic) > 80:
                cls = [c.split("::")[-1].strip() for c in md.get_all("Classifier") or [] if c.startswith("License ::")]
                lic = " OR ".join(cls) if cls else lic[:80]
        except Exception:
            lic = ""
        out[f"py:{p['name']}@{p['version']}"] = (lic, "requirements")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report")
    a = ap.parse_args()
    results = {}
    for name, fn in [("npm", npm_packages), ("npm-lock", npm_lockfiles), ("cargo", cargo_packages), ("go", go_packages), ("jvm", jvm_packages), ("python", python_packages)]:
        try:
            found = fn()
        except Exception as e:
            print(f"{name}: scan failed: {e}", file=sys.stderr)
            found = {}
        print(f"{name}: {len(found)} packages", file=sys.stderr)
        results.update(found)
    reviewed = json.loads((ROOT / "ci/licence-reviewed.json").read_text())["reviewed"]

    def hand_checked(pkg):
        name = re.sub(r"@[^@/]*$", "", pkg.split(":", 1)[1]) if pkg.count("@") or ":" in pkg else pkg
        name = re.sub(r"-\d[\w.\-]*\.jar$", "", name)
        for r in reviewed:
            if name in r.get("names", []):
                return r
            pat = r["name"]
            if pat != "*" and (name == pat or (pat.endswith("*") and name.startswith(pat[:-1]))):
                return r
        return None

    buckets = defaultdict(list)
    for pkg, (lic, where) in sorted(results.items()):
        c, text = classify(lic)
        if c in ("unknown", "review"):
            r = hand_checked(pkg)
            if r:
                c2, _ = classify(r["licence"])
                c, text = ("ok" if c2 == "ok" else "review"), f"{r['licence']} (reviewed: {r['why'][:90]})"
        buckets[c].append((pkg, text, where))
    lines = ["# Licence scan", "", "Generated by `ci/licence-scan.py`. Owner rule: free and open source only.", "",
             "| Class | Packages |", "|---|---|"] + [f"| {c} | {len(buckets[c])} |" for c in ("ok", "review", "unknown", "deny")]
    for c in ("deny", "review", "unknown"):
        if buckets[c]:
            lines += ["", f"## {c}", "", "| Package | Declared licence | Where |", "|---|---|---|"]
            lines += [f"| `{p}` | {t or '—'} | `{w}` |" for p, t, w in buckets[c]]
    text = "\n".join(lines) + "\n"
    if a.report:
        pathlib.Path(a.report).write_text(text)
    print("\n".join(lines[:10]))
    for c in ("deny",):
        for p, t, w in buckets[c]:
            print(f"DENY {p}: {t} ({w})")
    sys.exit(1 if buckets["deny"] else 0)


if __name__ == "__main__":
    main()
