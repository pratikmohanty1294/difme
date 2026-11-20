fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Process-level memory (owner rule): one runtime worker by default; appraisal is microseconds of CPU.
    let workers: usize = std::env::var("VERIFIER_WORKER_THREADS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    tokio::runtime::Builder::new_multi_thread().worker_threads(workers).enable_all().build()?.block_on(async {
        let verifier = difme_attestation_verifier::Verifier::new(difme_attestation_verifier::policy_from_env()?);
        let addr = std::env::var("VERIFIER_ADDR").unwrap_or_else(|_| "0.0.0.0:8443".into()).parse()?;
        eprintln!("attestation-verifier: gRPC (h2c; mTLS from the mesh) on {addr}");
        tonic::transport::Server::builder()
            .add_service(difme_attestation_verifier::proto::attestation_verifier_service_server::AttestationVerifierServiceServer::new(verifier))
            .serve_with_shutdown(addr, async { let _ = tokio::signal::ctrl_c().await; })
            .await?;
        Ok(())
    })
}
