# formio API server built from the vendored, pinned source (ADR-008). Context: vendor/formio/server.
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json yarn.lock* package-lock.json* ./
RUN corepack enable && if [ -f yarn.lock ]; then yarn install --frozen-lockfile --production --ignore-scripts; else npm ci --omit=dev --ignore-scripts; fi
COPY . .

FROM node:22-bookworm-slim
ENV NODE_ENV=production PORT=3001
WORKDIR /app
COPY --from=build --chown=1000:1000 /app /app
USER 1000
EXPOSE 3001
CMD ["node", "--max-old-space-size=384", "main"]
