#!/usr/bin/env python3
"""Runs the Feast feature models (models/features) on DuckDB against crafted staging rows and checks every value.
No Trino needed: the feature models use only plain SQL over staging columns.

  pip install dbt-core==1.12.7 dbt-duckdb && python -I analytics/dbt/tests_local/features_duckdb.py
"""
import os, subprocess, sys, tempfile
import duckdb

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
work = tempfile.mkdtemp(prefix="dbt-features-")
db = os.path.join(work, "iceberg.duckdb")
open(os.path.join(work, "profiles.yml"), "w").write(
    f"difme:\n  target: duck\n  outputs:\n    duck: {{ type: duckdb, path: '{db}', schema: analytics, threads: 1 }}\n")

c = duckdb.connect(db)
c.execute("create schema staging")
c.execute("""create table staging.stg_payment_events as select * from (values
  ('e1','p1','PaymentInitiated', localtimestamp - interval 10 minute, 100000::bigint, 'acc-A','cp-1'),
  ('e2','p2','PaymentInitiated', localtimestamp - interval 20 minute, 120000::bigint, 'acc-A','cp-2'),
  ('e3','p3','PaymentInitiated', localtimestamp - interval 30 minute,  80000::bigint, 'acc-A','cp-1'),
  ('e4','p4','PaymentInitiated', localtimestamp - interval 7 day,    100000::bigint, 'acc-A','cp-9'),
  ('e5','p5','PaymentInitiated', localtimestamp - interval 2 day,     50000::bigint, 'acc-B','cp-3'),
  ('e6','p6','PaymentInitiated', localtimestamp - interval 40 day,    50000::bigint, 'acc-C','cp-3'),
  ('e7','p1','PaymentCompleted', localtimestamp - interval 5 minute,  null,          null,  null)
) t(event_id, payment_id, event_name, event_timestamp, amount_minor, debit_account_id, credit_account_id)""")
c.execute("""create table staging.stg_edge_scores as select * from (values
  ('dev-1','p1', 0.05, true, localtimestamp - interval 10 day),
  ('dev-1','p2', 0.10, true, localtimestamp - interval 2 hour),
  ('dev-1','p3', 0.30, true, localtimestamp - interval 1 hour),
  ('dev-2','p5', 0.90, false, localtimestamp - interval 3 hour),
  ('dev-3','p6', 0.20, true, localtimestamp - interval 5 day)
) t(device_id, payment_id, fraud_probability, attested, scored_at)""")
c.close()

dbt = [os.path.join(os.path.dirname(sys.executable), "dbt"), "--no-use-colors"]
for cmd in (["run"], ["test"]):
    r = subprocess.run(dbt + cmd + ["--profiles-dir", work, "--target-path", os.path.join(work, "target"),
                                    "--log-path", work, "--select", "features"], cwd=PROJECT, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"dbt {cmd[0]} failed:\n{r.stdout[-3000:]}")

c = duckdb.connect(db, read_only=True)
acct = {r[0]: r[1:] for r in c.execute("select account_id, txn_count_1h, txn_amount_sum_1h, txn_amount_mean_30d, "
                                       "txn_amount_std_30d, distinct_counterparties_24h from features.account_velocity").fetchall()}
dev = {r[0]: r[1:] for r in c.execute("select device_id, geo_jump_km_1h, new_device_flag, edge_score_mean_24h from features.device_geo").fetchall()}
expect = [
    ("acc-A counts the 3 payments of the last hour", acct["acc-A"][0] == 3),
    ("acc-A 1 h sum is 300000 paise", acct["acc-A"][1] == 300000.0),
    ("acc-A 30 d mean is 100000", acct["acc-A"][2] == 100000.0),
    ("acc-A 30 d sample std is 16329.93", abs(acct["acc-A"][3] - 16329.93) < 0.01),
    ("acc-A has 2 counterparties in 24 h", acct["acc-A"][4] == 2),
    ("acc-B single payment: no std", acct["acc-B"][3] is None and acct["acc-B"][0] == 0),
    ("acc-C (only > 30 d) excluded", "acc-C" not in acct),
    ("dev-1 trust = mean(1 - p) * 1000 over 24 h = 800", abs(dev["dev-1"][2] - 800.0) < 1e-9),
    ("dev-1 not new (seen 10 d ago)", dev["dev-1"][1] == 0),
    ("dev-2 new (first seen 3 h ago), trust 100", dev["dev-2"][1] == 1 and abs(dev["dev-2"][2] - 100.0) < 1e-9),
    ("dev-3 no score in 24 h: trust null", dev["dev-3"][2] is None),
    ("geo jump null (no location in events yet)", all(v[0] is None for v in dev.values())),
]
for name, ok in expect:
    print(("ok   " if ok else "FAIL ") + name)
sys.exit(0 if all(ok for _, ok in expect) else 1)
