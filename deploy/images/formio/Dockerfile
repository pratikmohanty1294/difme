# formio API server built from the vendored, pinned source (ADR-008). Context: vendor/formio/server.
# Upstream ships no lockfile at the tag; dependencies resolve at build time and the exact tree is captured
# in the Syft SBOM attached to the image.
FROM node:22-bookworm AS build
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund
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
