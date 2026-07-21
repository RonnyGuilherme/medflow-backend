# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Cache dependencies separately from source code
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src ./src
RUN mvn package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="ronny.guilherme@hotmail.com"
LABEL org.opencontainers.image.title="MedFlow Orchestrator"
LABEL org.opencontainers.image.description="Appointment lifecycle and source-of-truth service"

WORKDIR /app

# Non-root user for security
RUN addgroup -S medflow && adduser -S orchestrator -G medflow

COPY --from=builder /app/target/*.jar app.jar

RUN chown orchestrator:medflow app.jar

USER orchestrator

EXPOSE 8080

# Enable Virtual Threads (Project Loom) — no extra flags needed in Java 21
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
