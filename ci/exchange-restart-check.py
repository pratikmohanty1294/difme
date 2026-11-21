#!/usr/bin/env python3
"""Restart check for exchange-svc's durable state (StateStore). Starts the packaged exchange against a Postgres-protocol
database, runs one full FR-01 order (standing mandate → intent → award → execute → delivery → half return), stops it,
starts it again and checks that everything came back and still behaves: the mandate's budget, awards, yield and status;
the auction result; the evidence chain (still verifies, operator seal and witness included); the delivery and the return
(a second return is refused); settlement pays only the yield on what was kept; new ids don't collide.

  python3 ci/exchange-restart-check.py <quarkus-run.jar> <jdbc-url> [java]
  e.g. jdbc:postgresql://127.0.0.1:15432/difme (YugabyteDB: jdbc:postgresql://yb-tservers.database-tier.svc:5433/difme)
"""
import json, os, subprocess, sys, tempfile, time, urllib.error, urllib.request

JAR, URL = sys.argv[1], sys.argv[2]
JAVA = sys.argv[3] if len(sys.argv) > 3 else "java"
BASE = "http://127.0.0.1:8089/v1/exchange"
keys = tempfile.mkdtemp(prefix="exchange-keys-")
checks = []


def check(name, ok):
    checks.append(ok)
    print(("ok   " if ok else "FAIL ") + name)


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method in ("POST", "PUT") else None)
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        return e.code, (json.loads(t) if t.strip() else {})


def start():
    # Return window override < 0: the pack's own 10 days, so the return is inside it.
    p = subprocess.Popen([JAVA, f"-Ddifme.exchange.store.url={URL}", f"-Ddifme.evidence.local-keys-dir={keys}", "-jar", JAR],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for _ in range(120):
        try:
            urllib.request.urlopen(BASE + "/keys", timeout=1).read()
            return p
        except OSError:
            if p.poll() is not None:
                sys.exit("exchange exited:\n" + p.stdout.read()[-3000:])
            time.sleep(0.5)
    p.kill()
    sys.exit("exchange did not start")


def stop(p):
    p.terminate()          # SIGTERM: @PreDestroy flushes the pending writes
    try:
        out, _ = p.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return out


p = start()
s, m = call("POST", "/fr01-buy/mandates", {"partnerRef": f"restart-{int(time.time())}", "limitMinor": 2500000, "maxPerOrderMinor": 2500000,
            "minYieldPct": 4, "depositMinor": 2000000, "budgetTotalMinor": 2500000, "merchantCount": 2, "goodsCount": 5,
            "validityDays": 5, "cardJurisdiction": "IN_DOMESTIC_AFA"})
if "mandateId" not in m:
    sys.exit(f"mandate refused: {m}")
mid = m["mandateId"]
s, o = call("POST", "/fr01-buy/intents", {"riskScore": 900, "offerSanctioned": True, "listMinor": 2000000, "discountBps": 2000,
            "feeMinor": 20000, "maxPriceMinor": 1900000})
aid = o["auctionId"]
for _ in range(100):
    s, r = call("GET", f"/auctions/{aid}")
    if r.get("award"):
        break
    time.sleep(0.05)
s, e = call("POST", f"/auctions/{aid}/execute", {"merchantId": "m-restart"})
s, f = call("POST", f"/auctions/{aid}/sim/delivery")
s, ret = call("POST", f"/auctions/{aid}/returns", {"fractionBps": 5000})
check("before restart: bought, delivered, half returned", e.get("fulfilled") and f.get("verified") and s == 200)
before = {"mandate": call("GET", f"/fr01-buy/mandates/{mid}")[1], "result": call("GET", f"/auctions/{aid}")[1],
          "evidence": call("GET", f"/auctions/{aid}/evidence")[1]}
check("the half return took half the yield off the winning mandate (64000 -> 32000)", before["mandate"].get("yieldEarnedMinor") == 32000)
check("the day-by-day history shows the same net yield", sum(before["mandate"].get("yieldByDay", {}).values()) == 32000)
s, cat = call("PUT", "/fr01-buy/attributes", {"attributes": [{"key": "merchant", "type": "string"}, {"key": "warrantyYears", "type": "number", "label": "Warranty (years)"}]})
check("attribute master list replaced", s == 200)
call("POST", f"/fr01-buy/mandates/{mid}/status", {"status": "PAUSED"})
out = stop(p)
check("clean shutdown flushed everything", "records not written" not in out)

p = start()
try:
    s, mm = call("GET", f"/fr01-buy/mandates/{mid}")
    check("mandate is back", s == 200)
    for k in ("budgetLeftMinor", "awards", "yieldEarnedMinor", "nodeRef", "partnerRef", "yieldByDay"):
        check(f"mandate {k} = {before['mandate'][k]}", mm.get(k) == before["mandate"][k])
    check("mandate status change made just before the stop is kept (PAUSED)", mm.get("status") == "PAUSED")
    s, cat2 = call("GET", "/fr01-buy/attributes")
    check("attribute master list survived the restart", [a["key"] for a in cat2.get("attributes", [])] == ["merchant", "warrantyYears"])
    s, rr = call("GET", f"/auctions/{aid}")
    check("auction result is back with the same award", s == 200 and rr.get("award") == before["result"]["award"])
    s, ev = call("GET", f"/auctions/{aid}/evidence")
    v = ev.get("verdict", {})
    check("evidence chain is back with the same artifacts", [a["hash"] for a in ev.get("artifacts", [])] == [a["hash"] for a in before["evidence"]["artifacts"]])
    check("evidence still verifies (hashes, links, operator seal, witness)", v.get("hashes") and v.get("links") and v.get("seal") and v.get("witnesses"))
    s, again = call("POST", f"/auctions/{aid}/returns", {"fractionBps": 5000})
    check("the return is remembered: a second one is refused", s == 409 and "already" in again.get("error", ""))
    s, ex = call("POST", f"/auctions/{aid}/execute", {"merchantId": "m-restart"})
    check("the execution is remembered: execute is idempotent", s == 200 and ex.get("boughtBy") == e.get("boughtBy"))
    s, o2 = call("POST", "/fr01-buy/intents", {"riskScore": 900, "offerSanctioned": True, "listMinor": 2000000, "discountBps": 2000,
                 "feeMinor": 20000, "maxPriceMinor": 1900000})
    check("new auction ids continue after the restored ones", s == 202 and o2["auctionId"] > aid)
finally:
    stop(p)
print(f"{sum(checks)}/{len(checks)} checks passed")
sys.exit(0 if all(checks) else 1)
