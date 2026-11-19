# Builds one back-office app into a static nginx image. Context: repo root (needs vendor/formio/js).
# docker build -f backoffice/Dockerfile --build-arg APP=settlements --build-arg OUT=dist .
FROM node:22-bookworm AS formio
WORKDIR /vendor/formio/js
COPY vendor/formio/js/ ./
# Lockfiles pin every dependency (2026-11-20: an unpinned build pulled webpack minutes after its release and hit a
# 404 while npm's CDN was still propagating it; unpinned builds also weaken the SLSA provenance).
RUN npm ci --no-audit --no-fund && npm run lib && npm run dist

FROM node:22-bookworm AS build
ARG APP
RUN corepack enable
WORKDIR /repo
COPY --from=formio /vendor/formio/js /repo/vendor/formio/js
COPY backoffice/ /repo/backoffice/
WORKDIR /repo/backoffice
RUN pnpm install --frozen-lockfile && pnpm turbo run build --filter "@difme/${APP}"

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
# Pull fixed OS packages so the Trivy CRITICAL gate passes even when the base image lags behind Alpine fixes.
USER root
RUN apk upgrade --no-cache
USER 101
ARG APP
ARG OUT=dist
COPY --from=build /repo/backoffice/apps/${APP}/${OUT}/ /usr/share/nginx/html/
COPY backoffice/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
