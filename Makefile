# Payment Simulation PoC — developer shortcuts for the local sandbox.
#
#   make up        # build + start the whole stack (detached)
#   make ps        # service status / health
#   make smoke     # one end-to-end simulation (POST -> poll GET -> result)
#   make load      # k6 load test (default rate)
#   make load-heavy# k6 high rate to exercise the rate limiter (429) / backpressure
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

# Infra + observability only (run the apps from the IDE/gradle if you like).
INFRA = kafka kafka-init redis postgres apicurio-registry kafka-ui \
        otel-collector jaeger prometheus grafana

.PHONY: help up up-infra build ps logs smoke load load-heavy down clean urls

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## Build and start the whole stack (detached)
	$(COMPOSE) up -d --build
	@echo "Stack starting. Run 'make ps' until apps are healthy, then 'make smoke'."

up-infra: ## Start only infra + observability (apps run separately)
	$(COMPOSE) up -d $(INFRA)

build: ## Build the application images
	$(COMPOSE) build

ps: ## Show service status / health
	$(COMPOSE) ps

logs: ## Follow application logs
	$(COMPOSE) logs -f api-service sbus-service core-mock

smoke: ## Run one end-to-end simulation and print the result
	BASE_URL=$(BASE_URL) API_KEY=$(API_KEY) ./scripts/smoke.sh

load: ## k6 load test (default rate) via the grafana/k6 container
	docker run --rm --network host \
	  -e BASE_URL=$(BASE_URL) -e API_KEY=$(API_KEY) \
	  -e RATE=$(K6_RATE) -e DURATION=$(K6_DURATION) \
	  -v "$(PWD)/load:/load" grafana/k6 run /load/k6-simulations.js

load-heavy: ## k6 high-rate load to trigger 429 / backpressure
	docker run --rm --network host \
	  -e BASE_URL=$(BASE_URL) -e API_KEY=$(API_KEY) \
	  -e RATE=$(K6_HEAVY_RATE) -e DURATION=$(K6_DURATION) \
	  -v "$(PWD)/load:/load" grafana/k6 run /load/k6-simulations.js

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
