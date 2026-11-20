#!/usr/bin/env python3
"""Live buy smoke through the shopbot PWA's own web server (its /api proxy to exchange-svc), the path a phone takes:
standing mandate -> intent -> auction -> execute -> evidence -> simulated delivery. Prints one line per step; exit 1 on
the first failure. Uses the API server's service proxy, so it needs only kubectl."""
import json, subprocess, sys, time

BASE = "/api/v1/namespaces/core-platform/services/pwa-shopbot:8080/proxy/api/v1/exchange"


def call(method, path, body=None):
    cmd = ["kubectl", "get" if method == "GET" else "create", "--raw", BASE + path]
    if body is not None:
        cmd += ["-f", "-"]
    p = subprocess.run(cmd, input=json.dumps(body) if body is not None else None, capture_output=True, text=True, timeout=30)
    if p.returncode != 0:
        raise RuntimeError(f"{method} {path}: {(p.stderr or p.stdout).strip()[:300]}")
    return json.loads(p.stdout) if p.stdout.strip() else {}


def main():
    t0 = time.time()
    step = lambda msg: print(f"  {time.time() - t0:5.1f}s {msg}")
    try:
        m = call("POST", "/fr01-buy/mandates", {"partnerRef": f"smoke-{int(t0)}", "limitMinor": 1600000, "maxPerOrderMinor": 1600000,
                 "minYieldPct": 4, "depositMinor": 1200000, "budgetTotalMinor": 1600000, "merchantCount": 2, "goodsCount": 5,
                 "validityDays": 5, "cardJurisdiction": "IN_DOMESTIC_AFA"})
        step(f"standing mandate {m['mandateId']} registered")
        o = call("POST", "/fr01-buy/intents", {"riskScore": 900, "offerSanctioned": True, "listMinor": 2000000, "discountBps": 2000,
                 "feeMinor": 20000, "maxPriceMinor": 1900000})
        aid = o["auctionId"]
        step(f"intent accepted: auction {aid}, standing bids {o.get('standingBids')}")
        r = {}
        for _ in range(100):
            r = call("GET", f"/auctions/{aid}")
            if r.get("award") or r.get("status") != "OPEN" and "ranked" in r:
                break
            time.sleep(0.1)
        if not r.get("award"):
            raise RuntimeError(f"no award: {json.dumps(r)[:200]}")
        s = r["award"]["split"]
        step(f"awarded to node {r['award']['winner']['nodeRef']}: A {s['a']} Y {s['yield']} F {s['fee']} buyer pays {s['b']}")
        e = call("POST", f"/auctions/{aid}/execute", {"merchantId": "m-smoke"})
        if not e.get("fulfilled"):
            raise RuntimeError(f"not bought: {json.dumps(e)[:200]}")
        step(f"bought by node {e['boughtBy']}, escrow {e['escrow']['state']}")
        ev = call("GET", f"/auctions/{aid}/evidence")
        v = ev["verdict"]
        step(f"evidence: hashes {v['hashes']} links {v['links']} seal {v['seal']} witnesses {v['witnesses']}")
        f = call("POST", f"/auctions/{aid}/sim/delivery")
        if not f.get("verified"):
            raise RuntimeError(f"delivery refused: {f.get('problems')}")
        step(f"delivered: escrow released {f['escrow']['releasedMinor']} of {f['escrow']['heldMinor']}, yield after the return window")
        print("  live buy: ok")
    except Exception as ex:  # noqa: BLE001 - smoke reports any failure the same way
        print(f"  live buy: FAILED - {ex}")
        sys.exit(1)


main()
