# Quick Setup

## First time (required before `docker compose up`)

```bash
# 1. Clone
git clone https://github.com/RonnyGuilherme/medflow-backend.git
cd medflow-backend

# 2. Generate Go dependency lockfile
cd availability-engine && go mod tidy && cd ..

# 3. Install Node dependencies
cd notification-dispatcher && npm install && cd ..

# 4. Start everything
cp .env.example .env
docker compose up --build
```

> **Note:** `docker compose up --build` takes ~3-4 minutes on first run
> (downloads base images, compiles Java, downloads Go modules).
> Subsequent runs start in ~30 seconds.

## Verify the stack is up

```bash
curl http://localhost:8000/health          # Kong proxy
curl http://localhost:8080/actuator/health # Orchestrator
curl http://localhost:8081/health          # Availability Engine
open http://localhost:8025                 # Mailhog (email UI)
open http://localhost:3000                 # Grafana (admin/admin)
```

## Run an end-to-end booking

```bash
make book
```
