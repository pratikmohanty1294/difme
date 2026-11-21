//! Server-side fraud scorer (PDF §5.7). Scores one order from six features with the gradient-boosted model trained by
//! `ml/fraud/train.py` and exported to ONNX. Answers in the exchange's risk convention: `riskScore` 0..1000, higher is
//! safer, so it can stand in for the edge score when on-device attestation fails (§11.1 FAIL path).

use axum::{Json, Router, extract::State, http::StatusCode, routing::{get, post}};
use ort::{session::Session, value::Tensor};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

pub mod feast;

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

/// An order to score from the online store: features come from Feast by account and device.
#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Order {
    pub account_id: String,
    pub device_id: String,
    pub amount_minor: i64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct OrderScore {
    #[serde(flatten)]
    pub score: Score,
    pub features: Features,
    /// Online features Feast didn't have (new account or device, expired), filled with the defaults in `derive`.
    pub missing: Vec<&'static str>,
}

/// Model features from the online store row. Missing values: no history → counts 0, z-score 0, no geo jump;
/// unknown device → new_device_flag 1 and a neutral-low edge score of 500 (no attestation history). Amounts are minor
/// units; the spread is floored at ₹1 so a near-constant history doesn't turn small differences into huge z-scores.
pub fn derive(amount_minor: i64, row: &std::collections::HashMap<String, f64>) -> (Features, Vec<&'static str>) {
    let mut missing = Vec::new();
    let mut get = |k: &'static str, default: f64| row.get(k).copied().unwrap_or_else(|| { missing.push(k); default });
    let count = get("txn_count_1h", 0.0);
    let mean = get("txn_amount_mean_30d", f64::NAN);
    let std = get("txn_amount_std_30d", f64::NAN);
    let cps = get("distinct_counterparties_24h", 0.0);
    let geo = get("geo_jump_km_1h", 0.0);
    let new_dev = get("new_device_flag", 1.0);
    let edge = get("edge_score_mean_24h", 500.0);
    let z = if mean.is_finite() && std.is_finite() { (amount_minor as f64 - mean) / std.max(100.0) } else { 0.0 };
    let f = Features {
        amount_zscore: z as f32, txn_count_1h: count.max(0.0) as f32, distinct_counterparties_24h: cps.max(0.0) as f32,
        geo_jump_km_1h: geo.max(0.0) as f32, new_device_flag: if new_dev >= 0.5 { 1.0 } else { 0.0 },
        edge_score_mean_24h: edge.clamp(0.0, 1000.0) as f32,
    };
    (f, missing)
}

#[derive(Clone)]
struct App {
    scorer: Arc<Scorer>,
    feast: Option<Arc<feast::Client>>,
}

pub fn router(scorer: Arc<Scorer>, feast: Option<Arc<feast::Client>>) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/model", get(|State(a): State<App>| async move {
            Json(serde_json::json!({ "model": a.scorer.version, "features": FEATURES, "feast": a.feast.is_some() }))
        }))
        .route("/v1/score", post(score))
        .route("/v1/score/order", post(score_order))
        .with_state(App { scorer, feast })
}

type ApiError = (StatusCode, Json<serde_json::Value>);
fn err(code: StatusCode, msg: impl Into<String>) -> ApiError {
    (code, Json(serde_json::json!({ "error": msg.into() })))
}

async fn score_order(State(a): State<App>, Json(o): Json<Order>) -> Result<Json<OrderScore>, ApiError> {
    if o.account_id.is_empty() || o.device_id.is_empty() || o.amount_minor <= 0 {
        return Err(err(StatusCode::UNPROCESSABLE_ENTITY, "accountId, deviceId and a positive amountMinor are required"));
    }
    let Some(feast) = a.feast else { return Err(err(StatusCode::SERVICE_UNAVAILABLE, "no feature store configured (FEAST_URL)")) };
    let row = feast.fetch(&o.account_id, &o.device_id).await.map_err(|e| err(StatusCode::BAD_GATEWAY, e))?;
    let (features, missing) = derive(o.amount_minor, &row);
    let score = a.scorer.score(&features).map_err(|e| err(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(OrderScore { score, features, missing }))
}

async fn score(State(a): State<App>, Json(f): Json<Features>) -> Result<Json<Score>, ApiError> {
    let s = a.scorer;
    if let Some(why) = f.invalid() {
        return Err((StatusCode::UNPROCESSABLE_ENTITY, Json(serde_json::json!({ "error": why }))));
    }
    // Inference is ~microseconds for 60 depth-3 trees: run inline rather than hop to a blocking pool.
    s.score(&f).map(Json).map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": e.to_string() }))))
}
