#!/usr/bin/env python3
"""Builds the central switch's configuration (Mojaloop Testing Toolkit in hub mode) from participants.json.

  ci/mojaloop-switch-config.py <participants.json> <out spec_files dir> [--endpoint FSP=URL ...]

Writes user_config.json (HUB_ONLY_MODE, each participant's endpoint) and rules_forward/default.json:
  GET /parties/{Type}/{ID}            -> the participant owning that MSISDN range (the account-lookup role)
  everything else (parties/quotes/transfers/transactionRequests/authorizations, callbacks and errors)
                                      -> the participant named in FSPIOP-Destination
for FSPIOP 1.0 and 1.1 (PH-EE's connector speaks 1.0, the SDK scheme adapter 1.1). --endpoint overrides an endpoint
(local runs)."""
import argparse
import json
import pathlib

ROUTED = [  # (method, operationPath) forwarded by FSPIOP-Destination
    ("put", "/parties/{Type}/{ID}"), ("put", "/parties/{Type}/{ID}/error"),
    ("post", "/quotes"), ("put", "/quotes/{ID}"), ("put", "/quotes/{ID}/error"),
    ("post", "/transfers"), ("put", "/transfers/{ID}"), ("put", "/transfers/{ID}/error"),
    ("post", "/transactionRequests"), ("put", "/transactionRequests/{ID}"), ("put", "/transactionRequests/{ID}/error"),
    ("get", "/authorizations/{ID}"), ("put", "/authorizations/{ID}"), ("put", "/authorizations/{ID}/error"),
]
VERSIONS = [(1, 0), (1, 1)]


def rule(rid, desc, conds, fsp, major, minor):
    return {"ruleId": rid, "priority": 1, "description": desc,
            "apiVersion": {"minorVersion": minor, "majorVersion": major, "type": "fspiop", "asynchronous": True},
            "conditions": {"all": conds}, "event": {"params": {"to": fsp, "dfspId": fsp}, "type": "FORWARD"},
            "type": "forward", "version": 1}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("participants")
    ap.add_argument("out")
    ap.add_argument("--endpoint", nargs="*", default=[])
    a = ap.parse_args()
    parts = json.loads(pathlib.Path(a.participants).read_text())["participants"]
    for o in a.endpoint:
        fsp, url = o.split("=", 1)
        parts[fsp]["endpoint"] = url
    rules, rid = [], 0
    for major, minor in VERSIONS:
        for fsp, p in parts.items():
            lo, hi = p["msisdn"]
            rid += 1
            rules.append(rule(rid, f"get /parties MSISDN {lo}-{hi} -> {fsp}", [
                {"fact": "pathParams", "operator": "numberStringGreaterThanInclusive", "value": lo, "path": "ID"},
                {"fact": "pathParams", "operator": "numberStringLessThanInclusive", "value": hi, "path": "ID"},
                {"fact": "operationPath", "operator": "equal", "value": "/parties/{Type}/{ID}"},
                {"fact": "method", "operator": "equal", "value": "get"}], fsp, major, minor))
            for method, path in ROUTED:
                rid += 1
                rules.append(rule(rid, f"{method} {path} -> {fsp}", [
                    {"fact": "headers", "operator": "equal", "value": fsp, "path": "fspiop-destination"},
                    {"fact": "operationPath", "operator": "equal", "value": path},
                    {"fact": "method", "operator": "equal", "value": method}], fsp, major, minor))
    out = pathlib.Path(a.out)
    (out / "rules_forward").mkdir(parents=True, exist_ok=True)
    (out / "rules_forward" / "default.json").write_text(json.dumps(rules, indent=1) + "\n")
    (out / "rules_forward" / "config.json").write_text(json.dumps({"activeRulesFile": "default.json"}) + "\n")
    user = {
        "VERSION": 1, "HUB_ONLY_MODE": True, "FSPID": "switch", "DEFAULT_USER_FSPID": "difme",
        "ENDPOINTS_DFSP_WISE": {"dfsps": {f: {"defaultEndpoint": p["endpoint"], "endpoints": []} for f, p in parts.items()}},
        "CALLBACK_ENDPOINT": "http://localhost:4040", "SEND_CALLBACK_ENABLE": True, "CALLBACK_TIMEOUT": 20000,
        "CALLBACK_RESOURCE_ENDPOINTS": {"enabled": False, "endpoints": []},
        "TRANSFERS_VALIDATION_WITH_PREVIOUS_QUOTES": False, "TRANSFERS_VALIDATION_ILP_PACKET": False,
        "TRANSFERS_VALIDATION_CONDITION": False, "ILP_SECRET": "switch-unused", "VERSIONING_SUPPORT_ENABLE": True,
        "VALIDATE_INBOUND_JWS": False, "VALIDATE_INBOUND_PUT_PARTIES_JWS": False, "JWS_SIGN": False, "JWS_SIGN_PUT_PARTIES": False,
        "INBOUND_MUTUAL_TLS_ENABLED": False, "OUTBOUND_MUTUAL_TLS_ENABLED": False, "ADVANCED_FEATURES_ENABLED": True,
    }
    (out / "user_config.json").write_text(json.dumps(user, indent=1) + "\n")
    print(f"{len(parts)} participants, {len(rules)} forward rules")


if __name__ == "__main__":
    main()
