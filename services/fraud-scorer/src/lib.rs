//! Server-side fraud scorer (PDF §5.7). Scores one order from six features with the gradient-boosted model trained by
//! `ml/fraud/train.py` and exported to ONNX. Answers in the exchange's risk convention: `riskScore` 0..1000, higher is
//! safer, so it can stand in for the edge score when on-device attestation fails (§11.1 FAIL path).

use axum::{Json, Router, extract::State, http::StatusCode, routing::{get, post}};
use ort::{session::Session, value::Tensor};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

/// Feature order the model was trained with (ml/fraud/train.py FEATURES); checked against model.json at start.
pub const FEATURES: [&str; 6] =
    ["amount_zscore", "txn_count_1h", "distinct_counterparties_24h", "geo_jump_km_1h", "new_device_flag", "edge_score_mean_24h"];

#[derive(Deserialize, Serialize, Clone, Copy, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Features {
    pub amount_zscore: f32,
    pub txn_count_1h: f32,
    pub distinct_counterparties_24h: f32,
    pub geo_jump_km_1h: f32,
    pub new_device_flag: f32,
    pub edge_score_mean_24h: f32,
}

impl Features {
    fn row(&self) -> [f32; 6] {
        [self.amount_zscore, self.txn_count_1h, self.distinct_counterparties_24h, self.geo_jump_km_1h, self.new_device_flag,
         self.edge_score_mean_24h]
    }
    fn invalid(&self) -> Option<&'static str> {
        let r = self.row();
        if r.iter().any(|v| !v.is_finite()) { return Some("features must be finite numbers"); }
        if r[1] < 0.0 || r[2] < 0.0 || r[3] < 0.0 { return Some("counts and distances cannot be negative"); }
        if r[4] != 0.0 && r[4] != 1.0 { return Some("newDeviceFlag must be 0 or 1"); }
        if !(0.0..=1000.0).contains(&r[5]) { return Some("edgeScoreMean24h must be within 0..1000"); }
        None
    }
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Score {
    /// Probability of fraud from the model.
    pub probability: f32,
    /// 0..1000, higher is safer (the exchange's riskScore convention).
    pub risk_score: u32,
    /// ALLOW below 0.15, REVIEW up to 0.5, BLOCK from 0.5.
    pub decision: &'static str,
    pub model: String,
}

pub struct Scorer {
    _env: ort::environment::Environment,
    session: Mutex<Session>,
    input: String,
    output: String,
    pub version: String,
}

impl Scorer {
    /// Loads ONNX Runtime from `runtime` (libonnxruntime.so) and the model. One intra-op thread: a 60-tree model is
    /// scored in microseconds and more threads would only add idle memory.
    pub fn load(runtime: &std::path::Path, model: &std::path::Path, version: String) -> Result<Self, String> {
        let env = ort::init_from(runtime).map_err(|e| format!("ONNX Runtime {}: {e}", runtime.display()))?
            .with_name("fraud-scorer").build().map_err(|e| e.to_string())?;
        let session = Session::builder(&env).map_err(|e| e.to_string())?
            .with_intra_threads(1).map_err(|e| e.to_string())?
            .commit_from_file(model).map_err(|e| e.to_string())?;
        let input = session.inputs()[0].name().to_string();
        let output = session.outputs().iter().map(|o| o.name().to_string()).find(|n| n == "probabilities")
            .unwrap_or_else(|| session.outputs()[session.outputs().len() - 1].name().to_string());
        Ok(Self { _env: env, session: Mutex::new(session), input, output, version })
    }

    pub fn score(&self, f: &Features) -> ort::Result<Score> {
        let x = Tensor::from_array(([1usize, 6], f.row().to_vec()))?;
        let mut s = self.session.lock().unwrap_or_else(|e| e.into_inner());
        let out = s.run(ort::inputs![self.input.as_str() => x])?;
        let (_, probs) = out[self.output.as_str()].try_extract_tensor::<f32>()?;
        let p = probs.get(1).copied().unwrap_or(probs[0]).clamp(0.0, 1.0);
        Ok(Score {
            probability: p,
            risk_score: ((1.0 - p) * 1000.0).round() as u32,
            decision: if p >= 0.5 { "BLOCK" } else if p >= 0.15 { "REVIEW" } else { "ALLOW" },
            model: self.version.clone(),
        })
    }
}

pub fn router(scorer: Arc<Scorer>) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/model", get(|State(s): State<Arc<Scorer>>| async move {
            Json(serde_json::json!({ "model": s.version, "features": FEATURES }))
        }))
        .route("/v1/score", post(score))
        .with_state(scorer)
}

async fn score(State(s): State<Arc<Scorer>>, Json(f): Json<Features>) -> Result<Json<Score>, (StatusCode, Json<serde_json::Value>)> {
    if let Some(why) = f.invalid() {
        return Err((StatusCode::UNPROCESSABLE_ENTITY, Json(serde_json::json!({ "error": why }))));
    }
    // Inference is ~microseconds for 60 depth-3 trees: run inline rather than hop to a blocking pool.
    s.score(&f).map(Json).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": e.to_string() }))))
}
