# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci --silent

COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM node:20-alpine

LABEL maintainer="ronny.guilherme@hotmail.com"
LABEL org.opencontainers.image.title="MedFlow Notification Dispatcher"

WORKDIR /app

# Non-root user
RUN addgroup -S medflow && adduser -S dispatcher -G medflow

COPY package*.json ./
RUN npm ci --omit=dev --silent

COPY --from=builder /app/dist ./dist

RUN chown -R dispatcher:medflow /app
USER dispatcher

EXPOSE 3001

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD \
  node -e "require('http').get('http://localhost:3001/health', r => process.exit(r.statusCode === 200 ? 0 : 1))"

CMD ["node", "dist/index.js"]
