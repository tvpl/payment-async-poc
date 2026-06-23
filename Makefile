# Payment Simulation PoC — developer shortcuts for the local sandbox.
#
#   make up        # build + start the whole stack (detached)
#   make demo      # up + wait for health + smoke (one-command guided tour)
#   make ps        # service status / health
#   make smoke     # one end-to-end simulation (POST -> poll GET -> result)
#   make load      # k6 load test (default rate)
#   make load-heavy# k6 high rate to exercise the rate limiter (429) / backpressure
#   make load-ramp # k6 ramping arrival rate (climb -> hold -> down)
#   make load-poll # k6 async path (POST 202 -> poll GET until terminal)
#   make logs      # follow the application logs
#   make urls      # print the useful URLs
#   make down      # stop the stack
#   make clean     # stop + remove volumes (fresh state)
.DEFAULT_GOAL := help

# --- tunables (override on the CLI: make load K6_RATE=300) ---------------------
BASE_URL      ?= http://localhost:8080
API_KEY       ?= dev-key-change-me
K6_RATE       ?= 100
K6_HEAVY_RATE ?= 400
K6_DURATION   ?= 1m
COMPOSE       ?= docker compose

# Push k6 metrics to Prometheus (so the "k6 Load Test" Grafana dashboard populates).
# Used by the load-* targets; requires the observability profile (default).
K6_PROM_URL   ?= http://localhost:9090/api/v1/write
K6_PROM_OUT    = -o experimental-prometheus-rw
K6_DOCKER      = docker run --rm --network host \
                   -e BASE_URL=$(BASE_URL) -e API_KEY=$(API_KEY) \
                   -e K6_PROMETHEUS_RW_SERVER_URL=$(K6_PROM_URL) \
                   -e K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg \
                   -v "$(PWD)/load:/load" grafana/k6 run $(K6_PROM_OUT)

# Infra + observability only (run the apps from the IDE/gradle if you like).
INFRA = kafka kafka-init redis postgres apicurio-registry kafka-ui \
        otel-collector jaeger prometheus grafana \
        redis-exporter postgres-exporter kafka-exporter

.PHONY: help up up-core up-infra build ps wait demo logs smoke \
        load load-heavy load-ramp load-poll down clean urls

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## Build and start the whole stack (detached)
	$(COMPOSE) up -d --build
	@echo "Stack starting. Run 'make ps' until apps are healthy, then 'make smoke'."

up-core: ## Start a lean stack (apps + infra, no observability)
	COMPOSE_PROFILES= $(COMPOSE) up -d --build

up-infra: ## Start only infra + observability (apps run separately)
	$(COMPOSE) up -d $(INFRA)

build: ## Build the application images
	$(COMPOSE) build

ps: ## Show service status / health
	$(COMPOSE) ps

wait: ## Block until api/sbus/core-mock are healthy
	@echo "Waiting for apps to become healthy..."
	@for i in $$(seq 1 60); do \
	  ok=1; \
	  for s in api-service sbus-service core-mock; do \
	    st=$$($(COMPOSE) ps --format '{{.Health}}' $$s 2>/dev/null); \
	    [ "$$st" = "healthy" ] || ok=0; \
	  done; \
	  [ $$ok -eq 1 ] && { echo "All apps healthy."; exit 0; }; \
	  sleep 3; \
	done; \
	echo "Timed out waiting for health; check 'make ps'."; exit 1

demo: up wait smoke ## up + wait for health + smoke (guided tour)
	@echo "Demo ready. Try 'make load' and watch Grafana (make urls)."

logs: ## Follow application logs
	$(COMPOSE) logs -f api-service sbus-service core-mock

smoke: ## Run one end-to-end simulation and print the result
	BASE_URL=$(BASE_URL) API_KEY=$(API_KEY) ./scripts/smoke.sh

load: ## k6 load test (default rate) -> Prometheus/Grafana
	$(K6_DOCKER) -e RATE=$(K6_RATE) -e DURATION=$(K6_DURATION) /load/k6-simulations.js

load-heavy: ## k6 high-rate load to trigger 429 / backpressure
	$(K6_DOCKER) -e RATE=$(K6_HEAVY_RATE) -e DURATION=$(K6_DURATION) /load/k6-simulations.js

load-ramp: ## k6 ramping arrival rate (climb -> hold -> down)
	$(K6_DOCKER) -e EXECUTOR=ramp -e RATE=$(K6_HEAVY_RATE) -e DURATION=$(K6_DURATION) /load/k6-simulations.js

load-poll: ## k6 async path: POST (202) -> poll GET until terminal
	$(K6_DOCKER) -e DURATION=$(K6_DURATION) /load/k6-poll.js

down: ## Stop the stack
	$(COMPOSE) down

clean: ## Stop the stack and remove volumes (fresh state)
	$(COMPOSE) down -v

urls: ## Print the useful URLs
	@echo "API            http://localhost:8080  (OpenAPI: /swagger/payment-simulation-api-1.0.yml)"
	@echo "SBUS           http://localhost:8081"
	@echo "core-mock      http://localhost:8082"
	@echo "Kafka UI       http://localhost:8088"
	@echo "Apicurio       http://localhost:8085"
	@echo "Prometheus     http://localhost:9090"
	@echo "Grafana        http://localhost:3000  (admin/admin)"
	@echo "Jaeger         http://localhost:16686"
