.PHONY: up down build test test-orchestrator test-availability test-notification logs clean seed

# ── Docker ───────────────────────────────────────────────────────────────────

up: ## Start all services
	cp -n .env.example .env 2>/dev/null || true
	docker compose up --build -d
	@echo "⏳ Waiting for services..."
	@sleep 10
	@docker compose ps

down: ## Stop all services
	docker compose down

build: ## Build all Docker images
	docker compose build

logs: ## Tail all service logs
	docker compose logs -f orchestrator availability-engine notification-dispatcher

clean: ## Stop and remove volumes
	docker compose down -v --remove-orphans

# ── Tests ─────────────────────────────────────────────────────────────────────

test: test-orchestrator test-availability test-notification ## Run all tests

test-orchestrator: ## Run Orchestrator tests (requires Docker for Testcontainers)
	cd orchestrator && mvn verify -B

test-availability: ## Run Availability Engine tests
	cd availability-engine && go test ./... -race -count=1

test-notification: ## Run Notification Dispatcher tests
	cd notification-dispatcher && npm ci --silent && npm test

# ── Dev helpers ───────────────────────────────────────────────────────────────

seed: ## Reseed development data
	docker exec medflow-postgres psql -U medflow -d medflow -f /docker-entrypoint-initdb.d/00_seed.sql

book: ## Quick end-to-end booking test (requires services running)
	@TOKEN=$$(grep DEV_JWT .env | cut -d= -f2) && \
	curl -s -X POST http://localhost:8000/api/v1/appointments \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -d '{"patientId":"00000000-0000-0000-0000-000000000010","professionalId":"00000000-0000-0000-0000-000000000020","slotId":"00000000-0000-0000-0000-000000000030","notes":"First consultation"}' \
	  | jq .

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
