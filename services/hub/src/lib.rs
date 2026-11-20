//! DIFME QUIC hub (PDF §5.2). Edge devices open a QUIC connection (ALPN `difme/1`) and send one payment per
//! bidirectional stream: a 4-byte big-endian length, then a protobuf `SubmitPaymentRequest`. The hub forwards it to
//! the core's `PaymentCommandService` over gRPC and answers on the same stream with the length-prefixed
//! `SubmitPaymentResponse`. Every outcome is also broadcast to live dashboards as server-sent events.

pub mod proto {
    tonic::include_proto!("difme.payments.v1");
}

use proto::payment_command_service_client::PaymentCommandServiceClient;
use proto::{SubmitPaymentRequest, SubmitPaymentResponse};
use prost::Message;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering::Relaxed};
use tokio::sync::broadcast;
use tonic::transport::{Channel, Endpoint};

pub const ALPN: &[u8] = b"difme/1";
/// Largest frame accepted from a device; a payment request is a few hundred bytes.
pub const MAX_FRAME: usize = 64 * 1024;

/// Counters exported at /metrics. Hand-rolled atomics instead of a metrics crate: a few bytes, no allocation.
#[derive(Default)]
pub struct Metrics {
    pub connections_open: AtomicI64,
    pub streams_total: AtomicU64,
    pub accepted_total: AtomicU64,
    pub duplicate_total: AtomicU64,
    pub rejected_total: AtomicU64,
    pub errors_total: AtomicU64,
}

impl Metrics {
    pub fn render(&self) -> String {
        format!(
            "# TYPE difme_hub_connections_open gauge\ndifme_hub_connections_open {}\n\
             # TYPE difme_hub_streams_total counter\ndifme_hub_streams_total {}\n\
             # TYPE difme_hub_payments_total counter\n\
             difme_hub_payments_total{{outcome=\"accepted\"}} {}\n\
             difme_hub_payments_total{{outcome=\"duplicate\"}} {}\n\
             difme_hub_payments_total{{outcome=\"rejected\"}} {}\n\
             # TYPE difme_hub_errors_total counter\ndifme_hub_errors_total {}\n",
            self.connections_open.load(Relaxed),
            self.streams_total.load(Relaxed),
            self.accepted_total.load(Relaxed),
            self.duplicate_total.load(Relaxed),
            self.rejected_total.load(Relaxed),
            self.errors_total.load(Relaxed)
        )
    }
}

/// Shared by the QUIC and HTTP sides.
pub struct State {
    pub metrics: Metrics,
    /// Outcomes for the live-dashboard relay; slow subscribers skip ahead instead of holding memory.
    pub events: broadcast::Sender<String>,
    pub core: PaymentCommandServiceClient<Channel>,
}

impl State {
    /// `core_url` is the gRPC endpoint (h2c), e.g. http://quarkus-cmd-svc.core-platform.svc:9000. Connects lazily.
    pub fn new(core_url: &str) -> Result<Arc<Self>, Box<dyn std::error::Error + Send + Sync>> {
        let channel = Endpoint::from_shared(core_url.to_string())?
            .connect_timeout(std::time::Duration::from_secs(2))
            .timeout(std::time::Duration::from_secs(5))
            .connect_lazy();
        let (events, _) = broadcast::channel(256);
        Ok(Arc::new(State { metrics: Metrics::default(), events, core: PaymentCommandServiceClient::new(channel) }))
    }
}

/// A QUIC endpoint plus the certificate it presents (dev: self-signed, so clients and tests can pin it).
pub struct QuicServer {
    pub endpoint: quinn::Endpoint,
    pub cert_der: Vec<u8>,
}

/// Binds the QUIC endpoint. Without a certificate pair a self-signed one is generated (dev and tests); production
/// mounts the SPIFFE X.509 SVID instead.
pub fn bind_quic(addr: SocketAddr, cert_and_key: Option<(Vec<u8>, Vec<u8>)>) -> Result<QuicServer, Box<dyn std::error::Error + Send + Sync>> {
    let (cert_der, key_der) = match cert_and_key {
        Some(pair) => pair,
        None => {
            let ck = rcgen::generate_simple_self_signed(vec!["quinn-hub".into(), "localhost".into()])?;
            (ck.cert.der().to_vec(), ck.signing_key.serialize_der())
        }
    };
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(cert_der.clone())],
            rustls::pki_types::PrivateKeyDer::Pkcs8(key_der.into()),
        )?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let mut server = quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(tls)?));
    let mut transport = quinn::TransportConfig::default();
    transport
        .max_concurrent_bidi_streams(64u32.into())          // per device: bounded in-flight payments
        .max_concurrent_uni_streams(0u32.into())
        .keep_alive_interval(Some(std::time::Duration::from_secs(15)));
    server.transport_config(Arc::new(transport));
    Ok(QuicServer { endpoint: quinn::Endpoint::server(server, addr)?, cert_der })
}

/// Accepts connections until the endpoint closes.
pub async fn serve_quic(endpoint: quinn::Endpoint, state: Arc<State>) {
    while let Some(incoming) = endpoint.accept().await {
        let state = state.clone();
        tokio::spawn(async move {
            let Ok(conn) = incoming.await else { return };
            state.metrics.connections_open.fetch_add(1, Relaxed);
            while let Ok((send, recv)) = conn.accept_bi().await {
                let state = state.clone();
                tokio::spawn(async move {
                    state.metrics.streams_total.fetch_add(1, Relaxed);
                    if handle_stream(send, recv, &state).await.is_err() {
                        state.metrics.errors_total.fetch_add(1, Relaxed);
                    }
                });
            }
            state.metrics.connections_open.fetch_sub(1, Relaxed);
        });
    }
}

async fn handle_stream(mut send: quinn::SendStream, mut recv: quinn::RecvStream, state: &State) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let request = SubmitPaymentRequest::decode(read_frame(&mut recv).await?.as_slice())?;
    let response = match state.core.clone().submit_payment(request).await {
        Ok(r) => r.into_inner(),
        Err(status) => {
            let _ = send.reset(quinn::VarInt::from_u32(1));     // 1 = core unavailable; the device retries (same idempotency key)
            return Err(Box::new(status));
        }
    };
    record(state, &response);
    write_frame(&mut send, &response.encode_to_vec()).await?;
    send.finish()?;
    Ok(())
}

fn record(state: &State, r: &SubmitPaymentResponse) {
    use proto::submit_payment_response::Status;
    let outcome = match r.status() {
        Status::Accepted => { state.metrics.accepted_total.fetch_add(1, Relaxed); "ACCEPTED" }
        Status::Duplicate => { state.metrics.duplicate_total.fetch_add(1, Relaxed); "DUPLICATE" }
        Status::RejectedFraud | Status::RejectedRateLimit => { state.metrics.rejected_total.fetch_add(1, Relaxed); r.status().as_str_name() }
        Status::Unspecified => "UNSPECIFIED",
    };
    if state.events.receiver_count() > 0 {
        let _ = state.events.send(format!(r#"{{"paymentId":"{}","status":"{}"}}"#, r.payment_id, outcome));
    }
}

pub async fn read_frame(recv: &mut quinn::RecvStream) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    let mut len = [0u8; 4];
    recv.read_exact(&mut len).await?;
    let n = u32::from_be_bytes(len) as usize;
    if n > MAX_FRAME {
        return Err(format!("frame of {n} bytes exceeds {MAX_FRAME}").into());
    }
    let mut buf = vec![0u8; n];
    recv.read_exact(&mut buf).await?;
    Ok(buf)
}

pub async fn write_frame(send: &mut quinn::SendStream, body: &[u8]) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    send.write_all(&(body.len() as u32).to_be_bytes()).await?;
    send.write_all(body).await?;
    Ok(())
}

/// Health, metrics and the live-dashboard relay (SSE) on one small HTTP listener.
pub fn http_router(state: Arc<State>) -> axum::Router {
    use axum::response::sse::{Event, KeepAlive, Sse};
    use axum::routing::get;
    use tokio_stream::StreamExt;
    use tokio_stream::wrappers::BroadcastStream;
    axum::Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/metrics", get({ let s = state.clone(); move || async move { s.metrics.render() } }))
        .route("/v1/events", get({
            let s = state.clone();
            move || async move {
                let stream = BroadcastStream::new(s.events.subscribe())
                    .filter_map(|m| m.ok().map(|data| Ok::<_, std::convert::Infallible>(Event::default().data(data))));
                Sse::new(stream).keep_alive(KeepAlive::default())
            }
        }))
}
