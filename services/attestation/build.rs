// Generates the gRPC server (and a client for the tests) from the repo's contract, api/proto.
// protoc comes from protoc-bin-vendored, so the build needs no system protobuf compiler.
fn main() -> Result<(), Box<dyn std::error::Error>> {
    // SAFETY: build scripts are single-threaded; nothing else reads the environment concurrently.
    unsafe { std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path()?) };
    let proto = "../../api/proto/difme/attestation/v1/attestation.proto";
    println!("cargo:rerun-if-changed={proto}");
    tonic_prost_build::configure().build_client(true).compile_protos(&[proto], &["../../api/proto"])?;
    Ok(())
}
