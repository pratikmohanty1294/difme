//! Appraisal of COSE-signed EATs: one clean pass, then each named check failing on its own.

use coset::{CborSerializable, CoseSign1Builder, HeaderBuilder, iana};
use difme_attestation_verifier::proto::VerifyRequest;
use difme_attestation_verifier::{Policy, Verifier, EAT_NONCE};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use std::collections::{HashMap, HashSet};

const WASM: &str = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
const NOW: i64 = 1_800_000_000_000;

fn key(seed: u8) -> SigningKey {
    SigningKey::from_slice(&[seed; 32]).unwrap()
}

fn eat(signer: &SigningKey, nonce: &[u8]) -> Vec<u8> {
    let claims = coset::cbor::Value::Map(vec![(coset::cbor::Value::Integer(EAT_NONCE.into()), coset::cbor::Value::Bytes(nonce.to_vec()))]);
    let mut payload = Vec::new();
    coset::cbor::into_writer(&claims, &mut payload).unwrap();
    CoseSign1Builder::new()
        .protected(HeaderBuilder::new().algorithm(iana::Algorithm::ES256).build())
        .payload(payload)
        .create_signature(b"", |data| { let s: Signature = signer.sign(data); s.to_vec() })
        .build()
        .to_vec()
        .unwrap()
}

fn verifier() -> Verifier {
    let mut device_keys = HashMap::new();
    device_keys.insert("dev-1".to_string(), *key(1).verifying_key());
    device_keys.insert("dev-2".to_string(), *key(2).verifying_key());
    Verifier::new(Policy {
        device_keys,
        approved_wasm: HashSet::from([WASM.to_string()]),
        revoked: HashSet::from(["dev-2".to_string()]),
    })
}

fn req(device: &str, token: Vec<u8>, nonce: &[u8], wasm: &str, fraud: f64) -> VerifyRequest {
    VerifyRequest { device_id: device.into(), eat_token: token, nonce: nonce.to_vec(), fraud_score: fraud, wasm_module_sha256: wasm.into() }
}

#[test]
fn clean_evidence_is_trusted_once() {
    let v = verifier();
    let (nonce, exp) = v.challenge("dev-1", NOW);
    assert_eq!(exp, NOW + 60_000);
    let r = v.verify(&req("dev-1", eat(&key(1), &nonce), &nonce, WASM, 0.1), NOW + 1_000);
    assert!(r.trusted, "{:?}", r.failed_checks);
    assert!(!r.fallback_server_check);
    let replay = v.verify(&req("dev-1", eat(&key(1), &nonce), &nonce, WASM, 0.1), NOW + 2_000);
    assert_eq!(replay.failed_checks, vec!["freshness"], "a nonce is single use");
    assert!(replay.fallback_server_check);
}

#[test]
fn each_check_fails_on_its_own() {
    let v = verifier();
    // expired nonce
    let (n, _) = v.challenge("dev-1", NOW);
    assert_eq!(v.verify(&req("dev-1", eat(&key(1), &n), &n, WASM, 0.0), NOW + 61_000).failed_checks, vec!["freshness"]);
    // nonce issued to another device
    let (n, _) = v.challenge("dev-9", NOW);
    assert_eq!(v.verify(&req("dev-1", eat(&key(1), &n), &n, WASM, 0.0), NOW).failed_checks, vec!["freshness"]);
    // signed by the wrong key
    let (n, _) = v.challenge("dev-1", NOW);
    assert_eq!(v.verify(&req("dev-1", eat(&key(7), &n), &n, WASM, 0.0), NOW).failed_checks, vec!["signature"]);
    // EAT carries a different nonce than the one presented
    let (n, _) = v.challenge("dev-1", NOW);
    assert_eq!(v.verify(&req("dev-1", eat(&key(1), b"other"), &n, WASM, 0.0), NOW).failed_checks, vec!["signature"]);
    // garbage token
    let (n, _) = v.challenge("dev-1", NOW);
    assert_eq!(v.verify(&req("dev-1", vec![1, 2, 3], &n, WASM, 0.0), NOW).failed_checks, vec!["signature"]);
    // unapproved Wasm build
    let (n, _) = v.challenge("dev-1", NOW);
    assert_eq!(v.verify(&req("dev-1", eat(&key(1), &n), &n, &"0".repeat(64), 0.0), NOW).failed_checks, vec!["binary_hash"]);
    // revoked platform (valid evidence otherwise)
    let (n, _) = v.challenge("dev-2", NOW);
    assert_eq!(v.verify(&req("dev-2", eat(&key(2), &n), &n, WASM, 0.0), NOW).failed_checks, vec!["revoked_platform"]);
}

#[test]
fn high_fraud_score_falls_back_even_when_trusted() {
    let v = verifier();
    let (n, _) = v.challenge("dev-1", NOW);
    let r = v.verify(&req("dev-1", eat(&key(1), &n), &n, WASM, 0.85), NOW);
    assert!(r.trusted);
    assert!(r.fallback_server_check);
}
