# Veraison (RATS RFC 9334 Verifier, ADR-002; Apache-2.0) publishes no container images, so CI builds one from a
# pinned source commit. One image carries VTS + verification + provisioning; the Deployment runs them as three
# containers in one pod talking over localhost. Plugins are compiled in (SCHEME_LOADER=builtin): no runtime plugin
# discovery, no plugin subprocesses (less memory, no exec of external binaries).
FROM golang:1.26-bookworm AS build
ARG VERAISON_REF=61cf6088c1dc4f69cdd08e6a05f75c9fd3b2c6a6
RUN git clone -q https://github.com/veraison/services.git /src && git -C /src checkout -q "$VERAISON_REF"
WORKDIR /src
ENV CGO_ENABLED=1 GOFLAGS=-trimpath
RUN set -eux; for s in vts/cmd/vts-service verification/cmd/verification-service provisioning/cmd/provisioning-service; do \
      go build -ldflags "-s -w -X github.com/veraison/services/config.SchemeLoader=builtin -X github.com/veraison/services/config.Version=${VERAISON_REF}" \
        -o /out/$(basename $s) ./$s; \
    done

FROM debian:bookworm-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates sqlite3 \
    && apt-get upgrade -y && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 616 veraison && useradd -u 616 -g veraison --system -m veraison
COPY --from=build /out/ /usr/local/bin/
COPY entrypoint.sh /usr/local/bin/veraison-entrypoint
WORKDIR /opt/veraison
USER 616:616
ENTRYPOINT ["/usr/local/bin/veraison-entrypoint"]
