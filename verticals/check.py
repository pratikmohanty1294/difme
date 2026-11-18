#!/usr/bin/env python3
"""Validates every vertical pack (ADR-020) against the kernel's contract. Run by `make check AREA=verticals` and CI.
A pack is config the kernel loads, so a bad pack has to fail here, not at the first auction."""
import pathlib
import sys
import xml.etree.ElementTree as ET

import yaml

ROOT = pathlib.Path(__file__).resolve().parent
DOMAINS = {"finance", "compute", "physical", "identity", "data"}
STATUS = {"live", "planned"}
AUCTIONS = {"sbba", "rfq"}
REQUIRED = ["id", "name", "goal", "status", "domain", "weights", "collateral", "auction", "price_basis", "settlement",
            "evidence", "rails", "dpi"]


def weights_ok(w, where, errs):
    try:
        vals = [float(w["price"]), float(w["reputation"]), float(w["latency"])]
    except (KeyError, TypeError, ValueError):
        errs.append(f"{where}: weights need price, reputation, latency"); return
    if any(v < 0 or v > 1 for v in vals) or abs(sum(vals) - 1) > 1e-9:
        errs.append(f"{where}: weights must be in [0,1] and sum to 1 (got {vals})")


def check(pack: pathlib.Path, errs: list[str], seen: set[str]) -> None:
    v = yaml.safe_load(pack.read_text(encoding="utf-8"))
    where = pack.parent.name
    for k in REQUIRED:
        if k not in v: errs.append(f"{where}: missing '{k}'")
    if errs and errs[-1].startswith(where): return
    if v["id"] in seen: errs.append(f"{where}: duplicate id {v['id']}")
    seen.add(v["id"])
    if v["status"] not in STATUS: errs.append(f"{where}: status must be one of {STATUS}")
    if v["domain"] not in DOMAINS: errs.append(f"{where}: domain must be one of {DOMAINS}")
    weights_ok(v["weights"], f"{where}.weights", errs)
    for flow, w in (v.get("flow_weights") or {}).items():
        weights_ok(w, f"{where}.flow_weights.{flow}", errs)
    c = v["collateral"]
    if not (0 < float(c["alpha"]) <= 1): errs.append(f"{where}: collateral.alpha must be in (0,1]")
    gammas = c["gamma_rev"].values() if isinstance(c["gamma_rev"], dict) else [c["gamma_rev"]]
    if any(float(g) < 1 for g in gammas): errs.append(f"{where}: collateral.gamma_rev must be >= 1")
    if int(c["min_minor"]) < 0: errs.append(f"{where}: collateral.min_minor must be >= 0")
    a = v["auction"]
    if a["mode"] not in AUCTIONS: errs.append(f"{where}: auction.mode must be one of {AUCTIONS}")
    if int(a["window_ms"]) <= 0 or int(a["latency_max_ms"]) <= 0 or int(a["backups"]) < 0:
        errs.append(f"{where}: auction window/latency must be > 0, backups >= 0")
    for f in v.get("rules", []) + ([v["intent_schema"]] if v.get("intent_schema") else []):
        p = pack.parent / f
        if not p.exists(): errs.append(f"{where}: missing file {f}"); continue
        if p.suffix == ".dmn":
            try: ET.parse(p)
            except ET.ParseError as e: errs.append(f"{where}: {f} is not valid XML: {e}")
    if v["status"] == "live" and not v.get("rules"):
        errs.append(f"{where}: a live vertical needs eligibility rules")


def main() -> int:
    errs: list[str] = []
    seen: set[str] = set()
    packs = sorted(ROOT.glob("*/vertical.yaml"))
    for p in packs: check(p, errs, seen)
    if len(packs) != 9: errs.append(f"expected the 9 DIFME verticals, found {len(packs)}")
    for e in errs: print("✗", e)
    if not errs: print(f"✓ {len(packs)} vertical packs valid ({sum(1 for p in packs if 'status: live' in p.read_text())} live)")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
