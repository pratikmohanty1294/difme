#!/usr/bin/env python3
"""Lists files under a directory that hold a real private key: a PEM block whose BEGIN line names a PRIVATE KEY and is
followed by base64 data (so source code that only mentions the header is not matched), or a Java/PKCS#12 key store.
Usage: ci/find-private-keys.py <dir> [--all]   (prints one path per line)
Source files (test fixtures embedded in code) are listed only with --all: they are reviewed, never deleted.
Known and accepted: vendor/hyperswitch/crates/router/src/services/encryption.rs (keys inside #[cfg(test)])."""
import pathlib
import re
import sys

BLOCK = re.compile(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----\r?\n(?:[A-Za-z0-9+/=:,\- ]*\r?\n)*?[A-Za-z0-9+/]{40,}={0,2}\r?\n")
STORES = {".jks", ".p12", ".pfx", ".keystore"}
SOURCE = {".rs", ".java", ".kt", ".go", ".js", ".mjs", ".cjs", ".ts", ".py", ".rb", ".scala", ".groovy", ".c", ".h", ".cpp"}
ALL = "--all" in sys.argv

for f in pathlib.Path(sys.argv[1]).rglob("*"):
    if not f.is_file() or f.is_symlink():
        continue
    if f.suffix.lower() in SOURCE and not ALL:
        continue
    if f.suffix.lower() in STORES:
        print(f)
        continue
    try:
        if f.stat().st_size > 2_000_000:
            continue
        if BLOCK.search(f.read_bytes()):
            print(f)
    except OSError:
        pass
