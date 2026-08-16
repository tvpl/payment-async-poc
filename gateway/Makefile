SHELL := /bin/bash
COMPOSE := docker compose

.PHONY: config up down logs smoke certs-export clean

# Validação estrutural sem subir nada (usada também no CI).
config:
	KEYCLOAK_ADMIN_PASSWORD=$${KEYCLOAK_ADMIN_PASSWORD:-config-check-only} $(COMPOSE) config -q
	python3 scripts/validate-config.py

up:
	$(COMPOSE) up -d
	@echo "aguardando o Envoy ficar pronto em http://localhost:$${GATEWAY_ADMIN_HOST_PORT:-9901}/ready ..."
	@for i in $$(seq 1 60); do \
		if curl -fsS "http://localhost:$${GATEWAY_ADMIN_HOST_PORT:-9901}/ready" >/dev/null 2>&1; then \
			echo "gateway pronto"; exit 0; \
		fi; sleep 2; \
	done; echo "gateway não ficou pronto a tempo"; exit 1

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f envoy

smoke:
	./scripts/smoke.sh

# Exporta o certificado de cliente + CA do volume para testar o listener mTLS
# com curl a partir do host (arquivos ficam em certs/local/, que é ignorado).
certs-export:
	mkdir -p certs/local
	$(COMPOSE) cp certs-init:/certs/ca.crt certs/local/ca.crt
	$(COMPOSE) cp certs-init:/certs/client.crt certs/local/client.crt
	$(COMPOSE) cp certs-init:/certs/client.key certs/local/client.key
	@echo "certificados exportados para certs/local/"

# Remove containers E o volume de certificados (força regeneração no próximo up).
clean:
	$(COMPOSE) down -v
