use difme_fraud_scorer::{FEATURES, Scorer, feast, router};
use std::{path::PathBuf, sync::Arc};

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let model = PathBuf::from(std::env::var("MODEL_PATH").unwrap_or_else(|_| "/model/model.onnx".into()));
    let meta: serde_json::Value = std::fs::read(model.with_extension("json")).ok()
        .and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    let trained: Vec<&str> = meta["features"].as_array().map(|a| a.iter().filter_map(|v| v.as_str()).collect()).unwrap_or_default();
    if !trained.is_empty() && trained != FEATURES {
        eprintln!("model {} was trained on features {trained:?}, this build sends {FEATURES:?}", model.display());
        std::process::exit(2);
    }
    let version = meta["version"].as_str().unwrap_or("unknown").to_string();
    let runtime = PathBuf::from(std::env::var("ORT_DYLIB_PATH").unwrap_or_else(|_| "/usr/lib/libonnxruntime.so".into()));
    let scorer = match Scorer::load(&runtime, &model, version) {
        Ok(s) => Arc::new(s),
        Err(e) => { eprintln!("cannot load {}: {e}", model.display()); std::process::exit(2); }
    };
    let addr = std::env::var("LISTEN").unwrap_or_else(|_| "0.0.0.0:8090".into());
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("fraud-scorer {} on {addr}", scorer.version);
    // Online features by account/device (POST /v1/score/order); without FEAST_URL only /v1/score (features in the body) works.
    let feast = std::env::var("FEAST_URL").ok().filter(|u| !u.is_empty())
        .map(|u| Arc::new(feast::Client::new(u, std::time::Duration::from_millis(300))));
    axum::serve(listener, router(scorer, feast))
        .with_graceful_shutdown(async { let _ = tokio::signal::ctrl_c().await; })
        .await
        .expect("serve");
}
