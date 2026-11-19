#!/usr/bin/env python3
# Reports whether each image reference (one per line in the file given) has an arm64 build. Anonymous registry API.
#   python3 ci/archcheck.py images.txt
import json, sys, urllib.request, re
ACC = "application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"
def get(url, tok=None):
    h = {"Accept": ACC, "User-Agent": "difme-archcheck"}
    if tok: h["Authorization"] = "Bearer " + tok
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30)
def token(reg, repo):
    try:
        get(f"https://{reg}/v2/")
    except urllib.error.HTTPError as e:
        a = e.headers.get("WWW-Authenticate", "")
        realm = re.search(r'realm="([^"]+)"', a); svc = re.search(r'service="([^"]+)"', a)
        if not realm: return None
        u = f"{realm.group(1)}?scope=repository:{repo}:pull" + (f"&service={svc.group(1)}" if svc else "")
        d = json.load(get(u)); return d.get("token") or d.get("access_token")
    return None
for line in open(sys.argv[1]):
    ref = line.strip()
    if not ref: continue
    name, tag = ref.rsplit(":", 1)
    parts = name.split("/")
    if "." in parts[0]: reg, repo = parts[0], "/".join(parts[1:])
    else: reg, repo = "registry-1.docker.io", name if "/" in name else "library/" + name
    try:
        t = token(reg, repo)
        m = json.load(get(f"https://{reg}/v2/{repo}/manifests/{tag}", t))
        archs = sorted({p["platform"]["architecture"] for p in m.get("manifests", []) if p.get("platform", {}).get("architecture") not in (None, "unknown")})
        print(("OK   " if "arm64" in archs else "NO-ARM64 ") + ref, archs or "(single-arch manifest)")
    except Exception as e:
        print("ERR  " + ref, e)
