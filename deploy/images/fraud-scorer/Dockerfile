# DIFME server-side fraud scorer (PDF §5.7). Context: repo root.
# 1) train: ml/fraud/train.py → model.onnx + model.json (seeded, so every build ships the same model; arch-neutral).
# 2) runtime: libonnxruntime.so for the target arch, taken from the official onnxruntime wheel (pinned version).
# 3) build: the Rust service, cross-compiled on the build platform (load-dynamic: nothing to link at build time).
# 4) distroless glibc image (it carries libstdc++, which ONNX Runtime needs).
ARG ORT_VERSION=1.31.1

FROM --platform=$BUILDPLATFORM python:3.12-slim AS train
WORKDIR /ml
COPY ml/fraud/requirements.txt ./
RUN pip install --no-cache-dir -q -r requirements.txt
COPY ml/fraud/train.py ./
RUN python -I train.py --out /model/model.onnx

FROM --platform=$BUILDPLATFORM python:3.12-slim AS runtime
ARG TARGETARCH
ARG ORT_VERSION
# Newer onnxruntime wheels are tagged manylinux_2_28 (glibc 2.28; distroless debian12 has 2.36).
RUN A=$([ "$TARGETARCH" = arm64 ] && echo aarch64 || echo x86_64) \
    && pip download -q --no-deps --only-binary=:all: --platform "manylinux_2_28_$A" --platform "manylinux_2_27_$A" \
       --python-version 3.12 -d /w "onnxruntime==${ORT_VERSION}" \
    && python -c "import zipfile,glob,sys; z=zipfile.ZipFile(glob.glob('/w/*.whl')[0]); n=[x for x in z.namelist() if '/capi/libonnxruntime.so.' in x][0]; open('/libonnxruntime.so','wb').write(z.read(n)); print(n)"

FROM --platform=$BUILDPLATFORM rust:1.97-bookworm AS build
ARG TARGETARCH
RUN case "$TARGETARCH" in \
      arm64) apt-get update -qq && apt-get install -y -qq gcc-aarch64-linux-gnu >/dev/null && rustup target add aarch64-unknown-linux-gnu ;; \
      amd64) rustup target add x86_64-unknown-linux-gnu ;; esac
ENV CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
COPY services/fraud-scorer /src/services/fraud-scorer
WORKDIR /src/services/fraud-scorer
RUN T=$([ "$TARGETARCH" = arm64 ] && echo aarch64-unknown-linux-gnu || echo x86_64-unknown-linux-gnu) \
    && cargo build --release --locked --target "$T" && cp "target/$T/release/difme-fraud-scorer" /fraud-scorer

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=runtime /libonnxruntime.so /usr/lib/libonnxruntime.so
COPY --from=train /model /model
COPY --from=build /fraud-scorer /usr/local/bin/fraud-scorer
ENV ORT_DYLIB_PATH=/usr/lib/libonnxruntime.so MODEL_PATH=/model/model.onnx
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/fraud-scorer"]
