# formio API server built from the vendored, pinned source (ADR-008). Context: vendor/formio/server.
# Open source only (2026-11-20, owner decision "shim it out"):
#   - package.json declares the runtime deps upstream's source requires (@formio/vm OSL-3.0, @formio/nextgen ISC);
#   - @formio/feature-flags (proprietary subscription licence upstream) resolves to our MIT stand-in in shims/;
#   - ckeditor5 is overridden from the commercial "LTS Edition" 47.x to the GPL-2.0-or-later 49.x line.
# package-lock.json pins the whole tree; the Syft SBOM attached to the image records it.
# isolated-vm (via @formio/vm) compiles natively here; the full bookworm image has python3/make/g++.
FROM node:22-bookworm AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY shims/ ./shims/
RUN npm ci --omit=dev --no-audit --no-fund
COPY src/ ./src/
COPY config/ ./config/
COPY *.js *.txt default-template.json ./

FROM node:22-bookworm-slim
ENV NODE_ENV=production PORT=3001
WORKDIR /app
COPY --from=build --chown=1000:1000 /app /app
USER 1000
EXPOSE 3001
CMD ["node", "--max-old-space-size=384", "main"]
