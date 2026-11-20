#!/usr/bin/env python3
"""Trains the server-side fraud scorer (PDF §5.7) and exports it to ONNX.

Features are the Feast views in ml/feast/features.py plus the order itself. Until real labelled orders exist the
training set is synthetic, drawn from the risk rules the exchange already applies (§7.4 L1: amount z-score > 3 is
high risk; velocity, new device, geo jump, weak edge attestation). The model is small on purpose: 60 depth-3 trees,
a few hundred KB, scored in microseconds by ONNX Runtime.

  python ml/fraud/train.py --out model.onnx [--seed 7]

Writes model.onnx and model.json (feature order, metrics, version). With MLFLOW_TRACKING_URI set, the run, metrics
and the ONNX file are also logged to MLflow.
"""
import argparse, json, os
import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split
from skl2onnx import to_onnx

# Order matters: the scorer sends features in exactly this order (services/fraud-scorer FEATURES).
FEATURES = [
    "amount_zscore",               # (order amount - txn_amount_mean_30d) / txn_amount_std_30d
    "txn_count_1h",
    "distinct_counterparties_24h",
    "geo_jump_km_1h",
    "new_device_flag",
    "edge_score_mean_24h",         # 0..1000, on-device attestation score (higher = more trusted)
]
VERSION = "fraud-gbt-0.1.0"


def synth(n, rng):
    z = rng.normal(0, 1.2, n) + rng.exponential(0.6, n) * (rng.random(n) < 0.08) * 4
    count = rng.poisson(1.5, n) + (rng.random(n) < 0.05) * rng.poisson(12, n)
    cp = rng.poisson(2, n) + (rng.random(n) < 0.04) * rng.poisson(15, n)
    geo = rng.exponential(5, n) + (rng.random(n) < 0.03) * rng.uniform(300, 3000, n)
    newdev = (rng.random(n) < 0.12).astype(float)
    edge = np.clip(rng.normal(820, 90, n) - (rng.random(n) < 0.06) * rng.uniform(200, 600, n), 0, 1000)
    X = np.column_stack([z, count, cp, geo, newdev, edge]).astype(np.float32)
    # Hidden risk the model has to learn (logistic over the rule signals, interactions included).
    logit = (-5.2 + 1.1 * np.maximum(z - 2, 0) + 0.18 * np.maximum(count - 4, 0) + 0.12 * np.maximum(cp - 5, 0)
             + 1.6 * (geo > 500) + 1.0 * newdev * (z > 1.5) + 0.012 * np.maximum(650 - edge, 0))
    y = (rng.random(n) < 1 / (1 + np.exp(-logit))).astype(int)
    return X, y


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="model.onnx")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--rows", type=int, default=60000)
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)
    X, y = synth(a.rows, rng)
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.25, random_state=a.seed, stratify=y)
    clf = GradientBoostingClassifier(n_estimators=60, max_depth=3, learning_rate=0.15, subsample=0.8, random_state=a.seed)
    clf.fit(Xtr, ytr)
    p = clf.predict_proba(Xte)[:, 1]
    metrics = {"roc_auc": round(float(roc_auc_score(yte, p)), 4), "pr_auc": round(float(average_precision_score(yte, p)), 4),
               "fraud_rate": round(float(y.mean()), 4), "rows": a.rows}
    onx = to_onnx(clf, Xtr[:1], options={id(clf): {"zipmap": False}}, target_opset={"": 17, "ai.onnx.ml": 3})
    with open(a.out, "wb") as f:
        f.write(onx.SerializeToString())
    meta = {"version": VERSION, "features": FEATURES, "metrics": metrics, "outputs": [o.name for o in onx.graph.output]}
    with open(os.path.splitext(a.out)[0] + ".json", "w") as f:
        json.dump(meta, f, indent=2)
    print(json.dumps(meta))
    if os.environ.get("MLFLOW_TRACKING_URI"):
        import mlflow
        mlflow.set_experiment("fraud-scorer")
        with mlflow.start_run(run_name=VERSION):
            mlflow.log_params({"n_estimators": 60, "max_depth": 3, "seed": a.seed, "rows": a.rows})
            mlflow.log_metrics({k: v for k, v in metrics.items() if k != "rows"})
            mlflow.log_artifact(a.out)


if __name__ == "__main__":
    main()
