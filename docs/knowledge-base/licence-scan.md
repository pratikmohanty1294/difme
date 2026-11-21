# Licence scan

Owner rule (2026-11-21): nothing but free and open source. Run `make licence-scan` (`ci/licence-scan.py`; hand-checked
licences in `ci/licence-reviewed.json`). Package results below; findings outside packages first.

## Findings and actions (2026-11-21 deep scan)

| Finding | Status |
|---|---|
| `org.mifos:interop-ilp-conditions` (needed by PH-EE's Mojaloop connector): "All Rights Reserved … use is prohibited without permission" | **Removed**: replaced by `services/fspiop-ilp-models` (from the FSPIOP spec) + vendored java-ilp-core (Apache-2.0) |
| `org.json:json` 20210307/20231013- in PH-EE: JSON License ("Good, not Evil"), not OSI | **Fixed**: forced to 20240303 (public domain) |
| Container images (68 referenced) | All OSI: Apache-2.0, BSD, MIT, PostgreSQL, PSF, GPL-2.0+CE |
| Helm charts (31) and their subcharts | Bundled Redis/Postgres/Elasticsearch/Cassandra/MySQL subcharts are disabled; Valkey and the shared Postgres are used. Bitnami images: none (bitnami/kubectl was already replaced by registry.k8s.io/kubectl) |
| Redis | None anywhere: Valkey (BSD-3) is the one cache; the local dev stack runs Valkey too |
| Terraform (BSL since 1.6) | Not used (`infra/` does not exist). Cloud scenarios, when built, use **OpenTofu** (MPL-2.0) |
| Semgrep | OSS engine (LGPL-2.1) with DIFME's own rules only (`policies/semgrep`); no registry rules (their licence is not OSI) |
| dbt | dbt-core (Apache-2.0), not the ELv2 dbt Fusion engine |
| CKEditor 5 (via Form.io) | GPL-2.0-or-later (dual with commercial): allowed. **Owner question**: in GPL mode it shows a "Powered by CKEditor" badge (removable under the GPL, but close to the no-attribution-display rule); Form.io can use Quill (BSD-3) instead |
| WTFPL: `dom-set`, `spark-md5` (Form.io) | Free (FSF), not OSI-approved: listed for the owner |
| `ring` 0.16.20 (ISC + OpenSSL licence) | Only in Hyperswitch's test harness, not in the router image (router uses ring 0.17, Apache-2.0 AND ISC) |
| Public domain / CC0 (reactive-streams, aopalliance, xz, LatencyUtils, tiny-keccak, notify, string-hash), CDLA-Permissive-2.0 (Mozilla CA roots), CC-BY-3.0 (SPDX id list) | Free; not OSI licences because they are dedications or data licences |

| Class | Packages |
|---|---|
| ok | 4605 |
| review | 19 |
| unknown | 0 |
| deny | 0 |

## review

| Package | Declared licence | Where |
|---|---|---|
| `cargo:notify@8.2.0` | CC0-1.0 | `vendor/hyperswitch` |
| `cargo:ring@0.16.20` | ISC AND OpenSSL (reviewed: ring 0.16.20 only in Hyperswitch's test_utils (not in the router image); router uses ring ) | `vendor/hyperswitch` |
| `cargo:tiny-keccak@2.0.2` | CC0-1.0 | `vendor/hyperswitch` |
| `cargo:webpki-root-certs@1.0.11` | CDLA-Permissive-2.0 | `vendor/hyperswitch` |
| `cargo:webpki-root-certs@1.0.12` | CDLA-Permissive-2.0 | `services/hub` |
| `cargo:webpki-roots@0.26.11` | CDLA-Permissive-2.0 | `vendor/hyperswitch` |
| `cargo:webpki-roots@1.0.10` | CDLA-Permissive-2.0 | `vendor/hyperswitch` |
| `jar:LatencyUtils-2.0.3.jar` | Public Domain, per Creative Commons CC0 | `vendor/mifos-ph-ee/connector-channel/build/libs/ph-ee-connector-channel-1.0.0-SNAPSHOT.jar` |
| `jar:aopalliance-1.0.jar` | Public Domain | `vendor/mifos-ph-ee/connector-ams-mifos/build/libs/ph-ee-connector-ams-mifos-1.0.0-SNAPSHOT.jar` |
| `jar:json-20240303.jar` | Public Domain | `vendor/mifos-ph-ee/connector-channel/build/libs/ph-ee-connector-channel-1.0.0-SNAPSHOT.jar` |
| `jar:org.latencyutils.LatencyUtils-2.0.3.jar` | Public Domain, per Creative Commons CC0 | `services/core/exchange-svc/target/quarkus-app/lib/main` |
| `jar:reactive-streams-1.0.3.jar` | CC0 | `vendor/mifos-ph-ee/connector-channel/build/libs/ph-ee-connector-channel-1.0.0-SNAPSHOT.jar` |
| `jar:xz-1.9.jar` | Public Domain | `vendor/mifos-ph-ee/connector-channel/build/libs/ph-ee-connector-channel-1.0.0-SNAPSHOT.jar` |
| `npm:dom-set@1.1.1` | WTFPL | `vendor/formio/server` |
| `npm:spark-md5@2.0.2` | WTFPL | `vendor/formio/server` |
| `npm:spdx-exceptions@2.5.0` | CC-BY-3.0 | `vendor/mojaloop/sdk-scheme-adapter` |
| `npm:spdx-license-ids@3.0.23` | CC0-1.0 | `vendor/mojaloop/sdk-scheme-adapter` |
| `npm:string-hash@1.1.3` | CC0-1.0 | `vendor/formio/server` |
| `py:numpy@2.5.3` | BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0 | `requirements` |
