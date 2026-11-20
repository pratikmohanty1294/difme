use std::net::SocketAddr;

fn env(name: &str, default: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| default.to_string())
}

fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Process-level memory (owner rule): two runtime workers unless told otherwise, not one per node CPU.
    let workers: usize = env("HUB_WORKER_THREADS", "2").parse()?;
    tokio::runtime::Builder::new_multi_thread().worker_threads(workers).enable_all().build()?.block_on(run())
}

async fn run() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let state = difme_quinn_hub::State::new(&env("HUB_GRPC_CORE", "http://127.0.0.1:9000"))?;
    let quic_addr: SocketAddr = env("HUB_QUIC_ADDR", "0.0.0.0:4443").parse()?;
    let cert = match (std::env::var("HUB_TLS_CERT_DER"), std::env::var("HUB_TLS_KEY_DER")) {
        (Ok(c), Ok(k)) => Some((std::fs::read(c)?, std::fs::read(k)?)),
        _ => None,
    };
    let quic = difme_quinn_hub::bind_quic(quic_addr, cert)?;
    eprintln!("quinn-hub: QUIC on udp/{quic_addr}, HTTP (health, metrics, SSE) on tcp/{}", env("HUB_HTTP_ADDR", "0.0.0.0:9100"));
    tokio::spawn(difme_quinn_hub::serve_quic(quic.endpoint, state.clone()));
    let listener = tokio::net::TcpListener::bind(env("HUB_HTTP_ADDR", "0.0.0.0:9100")).await?;
    axum::serve(listener, difme_quinn_hub::http_router(state))
        .with_graceful_shutdown(async { let _ = tokio::signal::ctrl_c().await; })
        .await?;
    Ok(())
}
