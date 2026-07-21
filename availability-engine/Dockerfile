# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Install certificates for HTTPS calls
RUN apk add --no-cache ca-certificates

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-w -s" -o availability-engine ./cmd/server

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# distroless for minimal attack surface
FROM gcr.io/distroless/static-debian12

LABEL maintainer="ronny.guilherme@hotmail.com"
LABEL org.opencontainers.image.title="MedFlow Availability Engine"

WORKDIR /app

COPY --from=builder /app/availability-engine .
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

EXPOSE 8081

ENTRYPOINT ["/app/availability-engine"]
