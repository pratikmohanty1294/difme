//! /v1/score/order against a stub Feast that answers with the shape recorded from Feast 0.67 (columns out of order,
//! NOT_FOUND for an unknown device). Needs FRAUD_MODEL and ORT_DYLIB_PATH like tests/score.rs; skipped without them.
use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::{Json, Router, routing::post};
use difme_fraud_scorer::{Scorer, feast, router};
use http_body_util::BodyExt;
use std::sync::Arc;
use tower::ServiceExt;

async fn stub_feast() -> String {
    let app = Router::new().route("/get-online-features", post(|Json(req): Json<serde_json::Value>| async move {
        let device = req["entities"]["device_id"][0].as_str().unwrap_or_default().to_string();
        let known = device == "dev-1";
        let dev = |v: serde_json::Value| if known { serde_json::json!({"values":[v],"statuses":["PRESENT"]}) }
                                         else { serde_json::json!({"values":[null],"statuses":["NOT_FOUND"]}) };
        Json(serde_json::json!({
            "results": [
                {"values":["acc-1"],"statuses":["PRESENT"]}, {"values":[device],"statuses":["PRESENT"]},
                {"values":[2],"statuses":["PRESENT"]}, {"values":[30000.0],"statuses":["PRESENT"]},
                {"values":[3],"statuses":["PRESENT"]}, {"values":[120000.0],"statuses":["PRESENT"]},
                dev(serde_json::json!(4.0)), dev(serde_json::json!(870.0)), dev(serde_json::json!(0))
            ],
            "metadata": {"feature_names": ["account_id","device_id","txn_count_1h","txn_amount_std_30d",
                "distinct_counterparties_24h","txn_amount_mean_30d","geo_jump_km_1h","edge_score_mean_24h","new_device_flag"]}
        }))
    }));
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", l.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
    url
}

async fn post_order(app: &Router, body: &str) -> (StatusCode, serde_json::Value) {
    let r = app.clone().oneshot(Request::post("/v1/score/order").header("content-type", "application/json")
        .body(Body::from(body.to_string())).unwrap()).await.unwrap();
    let s = r.status();
    (s, serde_json::from_slice(&r.into_body().collect().await.unwrap().to_bytes()).unwrap_or_default())
}

#[tokio::test]
async fn order_is_scored_from_online_features() {
    let (Ok(model), Ok(rt)) = (std::env::var("FRAUD_MODEL"), std::env::var("ORT_DYLIB_PATH")) else {
        eprintln!("FRAUD_MODEL / ORT_DYLIB_PATH not set: skipping"); return;
    };
    let scorer = Arc::new(Scorer::load(rt.as_ref(), model.as_ref(), "test".into()).unwrap());
    let feast = Arc::new(feast::Client::new(stub_feast().await, std::time::Duration::from_secs(2)));
    let app = router(scorer.clone(), Some(feast));

    // ₹1,500 against a ₹1,200 ± ₹300 history: z = 1, known device, good edge score → allowed
    let (s, v) = post_order(&app, r#"{"accountId":"acc-1","deviceId":"dev-1","amountMinor":150000}"#).await;
    assert_eq!(s, StatusCode::OK, "{v}");
    assert_eq!(v["features"]["amountZscore"], 1.0);
    assert_eq!(v["decision"], "ALLOW", "{v}");
    assert_eq!(v["missing"], serde_json::json!([]));

    // Unknown device: device features default (new device, edge 500) and are listed as missing
    let (s, v) = post_order(&app, r#"{"accountId":"acc-1","deviceId":"dev-x","amountMinor":150000}"#).await;
    assert_eq!(s, StatusCode::OK, "{v}");
    assert_eq!(v["features"]["newDeviceFlag"], 1.0);
    assert_eq!(v["missing"], serde_json::json!(["geo_jump_km_1h", "new_device_flag", "edge_score_mean_24h"]));

    let (s, _) = post_order(&app, r#"{"accountId":"acc-1","deviceId":"dev-1","amountMinor":0}"#).await;
    assert_eq!(s, StatusCode::UNPROCESSABLE_ENTITY);
    let (s, _) = post_order(&router(scorer, None), r#"{"accountId":"a","deviceId":"d","amountMinor":1}"#).await;
    assert_eq!(s, StatusCode::SERVICE_UNAVAILABLE);
}
