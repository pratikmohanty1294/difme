# DIFME evidence notary (independent witness). Context: repo root. Cross-compiled on the build
# platform for each target arch (no QEMU emulation of rustc), then copied into a distroless glibc image.
FROM --platform=$BUILDPLATFORM rust:1.97-bookworm AS build
ARG TARGETARCH
RUN case "$TARGETARCH" in \
      arm64) apt-get update -qq && apt-get install -y -qq gcc-aarch64-linux-gnu >/dev/null && rustup target add aarch64-unknown-linux-gnu ;; \
      amd64) rustup target add x86_64-unknown-linux-gnu ;; esac
ENV CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
WORKDIR /src
COPY services/notary /src/services/notary
WORKDIR /src/services/notary
RUN T=$([ "$TARGETARCH" = arm64 ] && echo aarch64-unknown-linux-gnu || echo x86_64-unknown-linux-gnu) \
    && cargo build --release --locked --target "$T" && cp "target/$T/release/difme-notary" /notary

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=build /notary /usr/local/bin/notary
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/notary"]
