//! DIFME attestation verifier (PDF §11.1, RATS RFC 9334). The hub asks for a nonce per device (`Challenge`), the device
//! answers with an EAT (RFC 8392 CWT in a COSE_Sign1, ES256) that carries that nonce, and `Verify` appraises it:
//!
//! * `freshness`         the nonce was issued to this device, is unexpired, and is used once;
//! * `signature`         the COSE_Sign1 verifies with the device's registered P-256 key and its EAT nonce claim matches;
//! * `binary_hash`       the reported Wasm module hash is on the approved list (from SLSA provenance);
//! * `revoked_platform`  the device is not on the revocation list.
//!
//! Anything but a clean pass sets `fallback_server_check`: the payment goes through the full server-side fraud check.

pub mod proto {
    tonic::include_proto!("difme.attestation.v1");
}

use coset::{CborSerializable, CoseSign1, TaggedCborSerializable};
use p256::ecdsa::signature::Verifier as _;
use p256::ecdsa::{Signature, VerifyingKey};
use proto::attestation_verifier_service_server::AttestationVerifierService;
use proto::{ChallengeRequest, ChallengeResponse, VerifyRequest, VerifyResponse};
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// EAT nonce claim key (RFC 9711, CWT claim 10).
pub const EAT_NONCE: i64 = 10;
pub const NONCE_TTL_MS: i64 = 60_000;
/// Fraud scores at or above this send the payment to the server-side check even when attestation passes.
pub const FRAUD_FALLBACK: f64 = 0.8;
/// Outstanding nonces kept at most; the oldest expire first (bounded memory under a challenge flood).
const MAX_NONCES: usize = 65_536;

pub struct Policy {
    pub device_keys: HashMap<String, VerifyingKey>,
    pub approved_wasm: HashSet<String>,
    pub revoked: HashSet<String>,
}

pub struct Verifier {
    policy: Policy,
    nonces: Mutex<HashMap<Vec<u8>, (String, i64)>>,   // nonce → (device, expires_at_ms)
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl Verifier {
    pub fn new(policy: Policy) -> Self {
        Verifier { policy, nonces: Mutex::new(HashMap::new()) }
    }

    pub fn challenge(&self, device_id: &str, now: i64) -> (Vec<u8>, i64) {
        let mut nonce = vec![0u8; 32];
        getrandom::fill(&mut nonce).expect("OS randomness");
        let expires = now + NONCE_TTL_MS;
        let mut n = self.nonces.lock().unwrap();
        if n.len() >= MAX_NONCES {
            n.retain(|_, (_, exp)| *exp > now);
            if n.len() >= MAX_NONCES {
                if let Some(oldest) = n.iter().min_by_key(|(_, (_, e))| *e).map(|(k, _)| k.clone()) { n.remove(&oldest); }
            }
        }
        n.insert(nonce.clone(), (device_id.to_string(), expires));
        (nonce, expires)
    }

    pub fn verify(&self, r: &VerifyRequest, now: i64) -> VerifyResponse {
        let mut failed = Vec::new();
        // freshness: issued to this device, not expired, single use (removed whatever the outcome).
        let issued = self.nonces.lock().unwrap().remove(&r.nonce);
        match issued {
            Some((device, exp)) if device == r.device_id && exp >= now => {}
            _ => failed.push("freshness"),
        }
        if !self.signature_ok(r) { failed.push("signature"); }
        if !self.policy.approved_wasm.contains(&r.wasm_module_sha256.to_ascii_lowercase()) { failed.push("binary_hash"); }
        if self.policy.revoked.contains(&r.device_id) { failed.push("revoked_platform"); }
        let trusted = failed.is_empty();
        VerifyResponse {
            trusted,
            failed_checks: failed.into_iter().map(String::from).collect(),
            fallback_server_check: !trusted || r.fraud_score >= FRAUD_FALLBACK,
        }
    }

    fn signature_ok(&self, r: &VerifyRequest) -> bool {
        let Some(key) = self.policy.device_keys.get(&r.device_id) else { return false };
        let sign1 = CoseSign1::from_tagged_slice(&r.eat_token).or_else(|_| CoseSign1::from_slice(&r.eat_token));
        let Ok(sign1) = sign1 else { return false };
        let sig_ok = sign1
            .verify_signature(b"", |sig, data| {
                let s = Signature::from_slice(sig).map_err(|_| ())?;
                key.verify(data, &s).map_err(|_| ())
            })
            .is_ok();
        sig_ok && eat_nonce(&sign1).is_some_and(|n| n == r.nonce)
    }
}

/// The EAT nonce claim from the CWT payload (a CBOR map keyed by integers).
fn eat_nonce(sign1: &CoseSign1) -> Option<Vec<u8>> {
    let payload = sign1.payload.as_ref()?;
    let value: coset::cbor::Value = coset::cbor::from_reader(payload.as_slice()).ok()?;
    value.as_map()?.iter().find_map(|(k, v)| {
        (k.as_integer().map(i128::from) == Some(EAT_NONCE as i128)).then(|| v.as_bytes().cloned()).flatten()
    })
}

#[tonic::async_trait]
impl AttestationVerifierService for Verifier {
    async fn challenge(&self, req: tonic::Request<ChallengeRequest>) -> Result<tonic::Response<ChallengeResponse>, tonic::Status> {
        let device = req.into_inner().device_id;
        if device.is_empty() { return Err(tonic::Status::invalid_argument("device_id is required")); }
        let (nonce, expires_at_ms) = Verifier::challenge(self, &device, now_ms());
        Ok(tonic::Response::new(ChallengeResponse { nonce, expires_at_ms }))
    }

    async fn verify(&self, req: tonic::Request<VerifyRequest>) -> Result<tonic::Response<VerifyResponse>, tonic::Status> {
        Ok(tonic::Response::new(Verifier::verify(self, &req.into_inner(), now_ms())))
    }
}

/// Policy from the environment: `VERIFIER_DEVICE_KEYS_FILE` (lines "device-id <SEC1 public key, hex>"),
/// `VERIFIER_APPROVED_WASM` and `VERIFIER_REVOKED` (comma-separated). Missing entries mean "none".
pub fn policy_from_env() -> Result<Policy, Box<dyn std::error::Error + Send + Sync>> {
    let list = |name: &str| -> HashSet<String> {
        std::env::var(name).unwrap_or_default().split(',').map(|s| s.trim().to_ascii_lowercase()).filter(|s| !s.is_empty()).collect()
    };
    let mut device_keys = HashMap::new();
    if let Ok(path) = std::env::var("VERIFIER_DEVICE_KEYS_FILE") {
        for line in std::fs::read_to_string(path)?.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
            let (device, hex) = line.split_once(char::is_whitespace).ok_or("device key line needs '<device> <hex>'")?;
            device_keys.insert(device.to_string(), VerifyingKey::from_sec1_bytes(&decode_hex(hex.trim())?)?);
        }
    }
    Ok(Policy { device_keys, approved_wasm: list("VERIFIER_APPROVED_WASM"), revoked: list("VERIFIER_REVOKED") })
}

fn decode_hex(s: &str) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    if s.len() % 2 != 0 { return Err("odd-length hex".into()); }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.into())).collect()
}
