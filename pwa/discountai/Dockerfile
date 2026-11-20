# One DiscountAI role app (shopbot | investor | exchange) as a static PWA behind nginx. Context: pwa/discountai.
#   docker build --build-arg APP=shopbot -t difme-pwa-shopbot pwa/discountai
FROM node:22-bookworm AS build
ARG APP
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx tsc --noEmit && npx vite build --mode "${APP}"

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
USER root
RUN apk upgrade --no-cache
USER 101
ARG APP
COPY --from=build /app/dist-${APP}/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
