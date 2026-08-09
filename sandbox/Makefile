SHELL := /bin/bash
COMPOSE_MINIMAL := docker compose -f compose.yml
COMPOSE_ALL := docker compose -f compose.yml -f compose.profiles.yml --profile observability --profile tools

.PHONY: config up up-all down smoke verify-profiles verify-ports verify-lifecycle verify-docs verify-structural verify-runtime verify

config:
	$(COMPOSE_MINIMAL) config -q
	$(COMPOSE_ALL) config -q

up:
	$(COMPOSE_MINIMAL) up -d --wait

up-all:
	$(COMPOSE_ALL) up -d --wait

down:
	$(COMPOSE_ALL) down

smoke:
	./smoke/verify.sh

verify-profiles:
	./smoke/verify-profiles.sh

verify-ports:
	./smoke/ports/validate_ports.py

verify-lifecycle:
	./smoke/lifecycle/validate_lifecycle.py

verify-docs:
	./scripts/verify-docs.sh

verify-structural: config verify-ports verify-lifecycle verify-docs
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_*.py' -v

verify-runtime: smoke verify-profiles

verify: verify-structural verify-runtime
