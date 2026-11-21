//! Scores against the real model and ONNX Runtime. Needs FRAUD_MODEL (model.onnx from ml/fraud/train.py) and
//! ORT_DYLIB_PATH (libonnxruntime.so); CI sets both. Skipped with a note when they are absent.
use axum::body::Body;
use axum::http::{Request, StatusCode};
use difme_fraud_scorer::{Features, Scorer, router};
use http_body_util::BodyExt;
use std::sync::Arc;
use tower::ServiceExt;

fn scorer() -> Option<Arc<Scorer>> {
    let (Ok(model), Ok(rt)) = (std::env::var("FRAUD_MODEL"), std::env::var("ORT_DYLIB_PATH")) else {
        eprintln!("FRAUD_MODEL / ORT_DYLIB_PATH not set: skipping");
        return None;
    };
    Some(Arc::new(Scorer::load(rt.as_ref(), model.as_ref(), "test".into()).expect("load")))
}

const SAFE: Features = Features { amount_zscore: 0.2, txn_count_1h: 1.0, distinct_counterparties_24h: 2.0, geo_jump_km_1h: 3.0,
                                  new_device_flag: 0.0, edge_score_mean_24h: 860.0 };
const RISKY: Features = Features { amount_zscore: 6.5, txn_count_1h: 14.0, distinct_counterparties_24h: 18.0, geo_jump_km_1h: 1400.0,
                                   new_device_flag: 1.0, edge_score_mean_24h: 280.0 };

#[test]
fn ordinary_order_is_allowed_and_a_stacked_risk_order_is_blocked() {
    let Some(s) = scorer() else { return };
    let safe = s.score(&SAFE).unwrap();
    let risky = s.score(&RISKY).unwrap();
    assert_eq!(safe.decision, "ALLOW", "{safe:?}");
    assert!(safe.risk_score >= 900, "{safe:?}");
    assert_eq!(risky.decision, "BLOCK", "{risky:?}");
    assert!(risky.probability > safe.probability * 10.0);
}

#[tokio::test]
async fn http_contract() {
    let Some(s) = scorer() else { return };
    let app = router(s, None);
    let body = serde_json::to_vec(&SAFE).unwrap();
    let r = app.clone().oneshot(Request::post("/v1/score").header("content-type", "application/json").body(Body::from(body)).unwrap())
        .await.unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let v: serde_json::Value = serde_json::from_slice(&r.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(v["decision"], "ALLOW");
    assert!(v["riskScore"].as_u64().unwrap() <= 1000);

    let bad = br#"{"amountZscore":0,"txnCount1h":-1,"distinctCounterparties24h":0,"geoJumpKm1h":0,"newDeviceFlag":0,"edgeScoreMean24h":800}"#;
    let r = app.clone().oneshot(Request::post("/v1/score").header("content-type", "application/json").body(Body::from(&bad[..])).unwrap())
        .await.unwrap();
    assert_eq!(r.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let r = app.oneshot(Request::post("/v1/score").header("content-type", "application/json").body(Body::from("{\"amountZscore\":1}")).unwrap())
        .await.unwrap();
    assert!(r.status().is_client_error());
}
