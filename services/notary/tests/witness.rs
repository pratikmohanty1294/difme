use axum::body::Body;
use axum::http::{Request, StatusCode};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use difme_notary::{Notary, Record, read_seed, router};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use http_body_util::BodyExt;
use tower::ServiceExt;

async fn call(app: &axum::Router, req: Request<Body>) -> (StatusCode, Vec<u8>) {
    let r = app.clone().oneshot(req).await.unwrap();
    let s = r.status();
    (s, r.into_body().collect().await.unwrap().to_bytes().to_vec())
}

#[tokio::test]
async fn witnesses_once_signs_raw_hash_and_logs() {
    let dir = std::env::temp_dir().join(format!("notary-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let log_path = dir.join("witness.log");
    let log = tokio::fs::OpenOptions::new().create(true).append(true).open(&log_path).await.unwrap();
    let n = Notary::new("notary-1".into(), [7u8; 32], 2, Some(log));
    let app = router(n.clone());

    let (s, body) = call(&app, Request::get("/v1/key").body(Body::empty()).unwrap()).await;
    assert_eq!(s, StatusCode::OK);
    let key: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(key["name"], "notary-1");
    let vk = VerifyingKey::from_bytes(&B64.decode(key["publicKey"].as_str().unwrap()).unwrap().try_into().unwrap()).unwrap();

    let hash = "AB".repeat(32);
    let req = || Request::post("/v1/witness").header("content-type", "application/json")
        .body(Body::from(format!("{{\"hash\":\"{hash}\"}}"))).unwrap();
    let (s, body) = call(&app, req()).await;
    assert_eq!(s, StatusCode::OK);
    let rec: Record = serde_json::from_slice(&body).unwrap();
    let sig = Signature::from_slice(&B64.decode(&rec.signature).unwrap()).unwrap();
    assert!(vk.verify(&hex::decode(&hash).unwrap(), &sig).is_ok(), "signs the raw 32 hash bytes");
    // Asked again: the same record, so the witnessed time cannot be moved.
    let (_, again) = call(&app, req()).await;
    assert_eq!(serde_json::from_slice::<Record>(&again).unwrap(), rec);
    let (s, _) = call(&app, Request::get(format!("/v1/witnessed/{}", hash.to_lowercase())).body(Body::empty()).unwrap()).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(std::fs::read_to_string(&log_path).unwrap().lines().count(), 1, "one durable line per witnessed hash");

    let (s, _) = call(&app, Request::post("/v1/witness").header("content-type", "application/json")
        .body(Body::from("{\"hash\":\"abcd\"}")).unwrap()).await;
    assert_eq!(s, StatusCode::BAD_REQUEST);
    let (s, _) = call(&app, Request::get(format!("/v1/witnessed/{}", "00".repeat(32))).body(Body::empty()).unwrap()).await;
    assert_eq!(s, StatusCode::NOT_FOUND);
    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn seeds_read_as_base64_or_hex() {
    assert_eq!(read_seed(&B64.encode([1u8; 32])).unwrap(), [1u8; 32]);
    assert_eq!(read_seed(&"02".repeat(32)).unwrap(), [2u8; 32]);
    assert!(read_seed("short").is_err());
}
