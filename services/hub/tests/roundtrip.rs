//! A device submits a payment over QUIC; the hub forwards it over gRPC to a fake core and answers on the same stream.

use difme_quinn_hub::proto::payment_command_service_server::{PaymentCommandService, PaymentCommandServiceServer};
use difme_quinn_hub::proto::{submit_payment_response::Status, Money, SubmitPaymentRequest, SubmitPaymentResponse};
use difme_quinn_hub::{bind_quic, read_frame, serve_quic, write_frame, State, ALPN};
use prost::Message;
use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;

struct FakeCore;

#[tonic::async_trait]
impl PaymentCommandService for FakeCore {
    async fn submit_payment(&self, req: tonic::Request<SubmitPaymentRequest>) -> Result<tonic::Response<SubmitPaymentResponse>, tonic::Status> {
        let r = req.into_inner();
        // Echo the idempotency key as the payment id; a repeated key is a duplicate.
        let status = if r.idempotency_key.ends_with("dup") { Status::Duplicate } else { Status::Accepted };
        Ok(tonic::Response::new(SubmitPaymentResponse { payment_id: r.idempotency_key, workflow_id: "wf".into(), status: status as i32 }))
    }
}

async fn start_core() -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(tonic::transport::Server::builder()
        .add_service(PaymentCommandServiceServer::new(FakeCore))
        .serve_with_incoming(tokio_stream::wrappers::TcpListenerStream::new(listener)));
    format!("http://{addr}")
}

fn client(cert_der: &[u8]) -> quinn::Endpoint {
    let mut roots = rustls::RootCertStore::empty();
    roots.add(rustls::pki_types::CertificateDer::from(cert_der.to_vec())).unwrap();
    let mut tls = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13]).unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let mut ep = quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).unwrap();
    ep.set_default_client_config(quinn::ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(tls).unwrap())));
    ep
}

fn payment(key: &str) -> SubmitPaymentRequest {
    SubmitPaymentRequest {
        idempotency_key: key.into(),
        debit_account_id: "a".into(),
        credit_account_id: "b".into(),
        amount: Some(Money { amount_minor: 12_345, currency: "INR".into() }),
        ..Default::default()
    }
}

#[tokio::test]
async fn payments_round_trip_over_quic_and_are_counted() {
    let state = State::new(&start_core().await).unwrap();
    let server = bind_quic("127.0.0.1:0".parse().unwrap(), None).unwrap();
    let addr = server.endpoint.local_addr().unwrap();
    let cert = server.cert_der.clone();
    tokio::spawn(serve_quic(server.endpoint, state.clone()));

    let conn = client(&cert).connect(addr, "localhost").unwrap().await.unwrap();
    for (key, expected) in [("0190f3c0-0000-7000-8000-000000000001", Status::Accepted), ("0190f3c0-0000-7000-8000-00000000dup", Status::Duplicate)] {
        let (mut send, mut recv) = conn.open_bi().await.unwrap();
        write_frame(&mut send, &payment(key).encode_to_vec()).await.unwrap();
        send.finish().unwrap();
        let resp = SubmitPaymentResponse::decode(read_frame(&mut recv).await.unwrap().as_slice()).unwrap();
        assert_eq!(resp.payment_id, key);
        assert_eq!(resp.status(), expected);
    }
    // An oversized frame is refused without reaching the core.
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(&(1_000_000u32).to_be_bytes()).await.unwrap();
    let _ = send.finish();
    assert!(read_frame(&mut recv).await.is_err());

    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    assert_eq!(state.metrics.accepted_total.load(Relaxed), 1);
    assert_eq!(state.metrics.duplicate_total.load(Relaxed), 1);
    assert_eq!(state.metrics.streams_total.load(Relaxed), 3);
    assert_eq!(state.metrics.errors_total.load(Relaxed), 1);
    assert!(state.metrics.render().contains("difme_hub_payments_total{outcome=\"accepted\"} 1"));
}
