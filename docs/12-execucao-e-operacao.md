# 12 — Execução e operação

## Pré-requisitos
- **Docker** + Docker Compose (para o stack completo).
- **JDK 21** (alvo atual; ver caminho para 25 abaixo) e o wrapper `./gradlew` (build local).
- Opcional: **k6** (teste de carga).

## Atalhos (Makefile)

Um [`Makefile`](../Makefile) na raiz cobre o ciclo todo (não exige k6 no host — usa o
container `grafana/k6`):

```bash
make up          # build + sobe o stack completo (detached)
make up-core     # stack enxuto (apps + infra, sem observabilidade)
make demo        # up + espera ficar healthy + smoke (tour em um comando)
make ps          # status / health dos serviços
make wait        # bloqueia até api/sbus/core-mock ficarem healthy
make smoke       # 1 simulação ponta a ponta (POST -> poll GET -> resultado)
make load        # teste de carga k6 (taxa padrão) -> métricas no Grafana
make load-heavy  # taxa alta para exercitar rate limit (429) / backpressure
make load-ramp   # arrival-rate em rampa (sobe -> mantém -> desce)
make load-poll   # caminho assíncrono: POST (202) -> poll GET até terminal
make logs        # acompanha os logs dos apps
make urls        # lista as URLs úteis
make down        # para o stack   |   make clean = down + remove volumes
```

Variáveis sobrescrevíveis: `make load K6_RATE=300 K6_DURATION=2m`, `API_KEY=...`, `BASE_URL=...`.
Os alvos `load*` empurram métricas do k6 para o Prometheus (dashboard **k6 Load Test** no Grafana).

## Configuração (.env) e profiles

Tunables ficam em [`.env`](../.env) (auto-carregado pelo compose): `API_KEY`,
`PAYMENT_SECURITY_ENABLED`, credenciais do Postgres, `KAFKA_TOPIC_PARTITIONS/RF`,
comportamento do Core (abaixo) e `COMPOSE_PROFILES`.

A pilha de **observabilidade** (Jaeger, OTel Collector, Prometheus, Grafana e os
exporters) fica atrás do profile `observability`, ligado por padrão via
`COMPOSE_PROFILES=observability`. Para um stack enxuto: `make up-core` (ou
`COMPOSE_PROFILES= docker compose up -d`).

## Demonstrações didáticas (core-mock configurável)

O core-mock lê o comportamento de `.env`/env, então dá para exercitar regimes sem rebuild:

| Variável | Efeito | Demonstra |
|---|---|---|
| `CORE_LATENCY_MAX_MS` (ex.: 5000) | Core lento | espera estoura → `202`, `api_timeouts_total`, fallback do `GET` |
| `CORE_DECLINE_PCT` (ex.: 80) | Mais recusas | `422` e `api_failed_total` |
| `CORE_FAIL_PCT` (ex.: 50) | Erro transitório no Core | retry topics (`*.retry`) e, no limite, **DLQ** |

```bash
# ex.: forçar timeouts e ver o caminho assíncrono
CORE_LATENCY_MIN_MS=4000 CORE_LATENCY_MAX_MS=6000 docker compose up -d core-mock
# ex.: exercitar retry/DLQ
CORE_FAIL_PCT=50 docker compose up -d core-mock
```
Derrubar o core-mock (`docker compose stop core-mock`) e gerar carga demonstra a
**durabilidade da outbox** + `OutboxReaper` (eventos publicam quando o Core volta).

## Subir o stack completo

```bash
docker compose up -d --build   # ou: make up
docker compose ps   # aguarde healthchecks: kafka, postgres, redis, apicurio, kafka-init
                    # e os 3 apps (api/sbus/core-mock) ficarem "healthy" (GET /health)
```

> Os apps agora têm **healthcheck** no compose (`/health`), então `docker compose ps` mostra
> `healthy` quando cada serviço está pronto — útil para esperar antes de disparar carga.

## Smoke test (validação rápida)

```bash
make smoke   # ou: ./scripts/smoke.sh
```
Dispara um `POST`, segue o `requestId` pelos status até um estado terminal
(`COMPLETED`/`FAILED`/`TIMEOUT`) e imprime o resultado — confirma o fluxo
API→Kafka→SBUS→core-mock→API. Ver [`scripts/smoke.sh`](../scripts/smoke.sh).

### Portas / URLs

| Serviço | URL |
|---|---|
| API | http://localhost:8080 |
| API — OpenAPI | http://localhost:8080/swagger/payment-simulation-api-1.0.yml |
| SBUS | http://localhost:8081 |
| core-mock | http://localhost:8082 |
| Apicurio Schema Registry | http://localhost:8085 |
| Kafka UI | http://localhost:8088 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Jaeger (traces) | http://localhost:16686 |
| Métricas | `:8080/prometheus`, `:8081/prometheus`, `:8082/prometheus` |
| Kafka (host) | `localhost:29092` |

## Exemplos curl

```bash
# Simulação (200 se o Core responder no prazo, ou 202). Requer X-API-Key (default dev).
curl -i -X POST http://localhost:8080/payment-simulations \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key-change-me' \
  -H 'Idempotency-Key: pedido-123' \
  -d '{"merchantId":"MERCHANT-001","amount":125.50,"currency":"BRL",
       "paymentMethod":"CREDIT_CARD","brand":"VISA","installments":3,
       "captureMode":"AUTHORIZE_AND_CAPTURE"}'

# Status por requestId
curl -i -H 'X-API-Key: dev-key-change-me' http://localhost:8080/payment-simulations/<requestId>

# Sem/!X-API-Key inválida -> 401. Idempotência: repetir com a MESMA Idempotency-Key reusa o requestId.
```

> Auth e segredos: `PAYMENT_SECURITY_ENABLED=false` desativa a auth (dev); `PAYMENT_API_KEY` define a
> chave. Produção: JWT/OAuth2 + mTLS (ver [15](15-prontidao-producao.md)).

## Teste de carga (k6)

```bash
# via Makefile (container grafana/k6, sem instalar k6; métricas vão p/ o Grafana):
make load                      # taxa padrão (100/s)
make load-heavy                # taxa alta (400/s) — exercita o rate limit
make load-ramp                 # arrival-rate em rampa (sobe -> mantém -> desce)
make load-poll                 # caminho assíncrono (POST 202 -> poll GET)

# ou diretamente com k6 instalado. A auth fica ON por padrão, então passe a chave:
k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=dev-key-change-me \
       -e RATE=300 -e DURATION=1m load/k6-simulations.js
k6 run -e EXECUTOR=ramp -e RATE=400 load/k6-simulations.js     # rampa
```
Espere uma mistura de `200/202/422` e alguns `429` (rate limit) sob taxa alta. O script
envia `X-API-Key` e marca `200/202/422/429` como respostas **esperadas**
(`http.expectedStatuses`), então o threshold `http_req_failed` só acusa erros reais
(ex.: `401` por chave errada, `5xx`). Os alvos `make load*` exportam as métricas via
remote-write para o Prometheus → dashboard **k6 Load Test** no Grafana, lado a lado com
as métricas dos serviços. Scripts: [`load/k6-simulations.js`](../load/k6-simulations.js)
(síncrono) e [`load/k6-poll.js`](../load/k6-poll.js) (assíncrono).

## Desenvolvimento local (sem compose para os apps)

Kafka exposto em `localhost:29092`; use o profile `dev` para desligar o export OTLP:

```bash
docker compose up -d kafka kafka-init redis postgres apicurio-registry kafka-ui otel-collector jaeger prometheus grafana
# ou: make up-infra
export MICRONAUT_ENVIRONMENTS=dev
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :sbus-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :api-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :core-mock:run
```

## Inspeção rápida

```bash
# Estado e outbox no Postgres
docker compose exec postgres psql -U sbus -d sbus -c \
  "select request_id,status from payment_sbus_message order by created_at desc limit 5;"
docker compose exec postgres psql -U sbus -d sbus -c \
  "select status,count(*) from outbox_event group by status;"

# Schemas registrados no Apicurio
curl -s http://localhost:8085/apis/registry/v2/search/artifacts | jq .
```

Para inspecionar **mensageria** visualmente (tópicos, mensagens, partições, consumer groups
e **lag**), abra o **Kafka UI** em http://localhost:8088. Ele resolve os schemas Avro via o
endpoint Confluent-compatível do Apicurio, então o payload dos eventos é decodificado na tela.

## Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| `401` nas chamadas | Falta `X-API-Key` | Enviar a chave (`dev-key-change-me`) ou `PAYMENT_SECURITY_ENABLED=false` em dev |
| App não sobe por erro OTLP | Collector ausente em dev | `MICRONAUT_ENVIRONMENTS=dev` (desliga export) |
| Mensagens reprocessando em loop | Falha transitória persistente | Ver tópicos `*.retry` e a DLQ; checar dependência abaixo |
| `GET` fica em `SENT_TO_SBUS` | Evento final ainda não processado | Aguardar; o `GET` cai para o SBUS (durável) |
| Muitos `429` | Rate limit de admissão | Ajustar `payment.simulation.rate-limit.*` |
| `outbox_event` crescendo | Kafka indisponível / publish falhando | Ver `sbus_outbox_pending`, logs do dispatcher |
| Mensagens na DLQ | Poison/erro permanente | Inspecionar `payment.simulation.dlq` (headers `x-dlq-*`) |
| `Could not find a valid Docker environment` (testes) | Sem Docker | Rodar só unit: `--tests '*UnitTest'` |

## Kafka de produção (multi-broker)
Subir tópicos com `KAFKA_TOPIC_RF=3` no `kafka-init` e usar um cluster com RF=3 /
`min.insync.replicas=2`. Exemplo ilustrativo (não testado aqui):
[`deploy/docker-compose.kafka-cluster.example.yml`](../deploy/docker-compose.kafka-cluster.example.yml).
Detalhes no [checklist de produção](15-prontidao-producao.md).

## Subir para Java 25
Trocar `javaLanguageVersion` em [`gradle.properties`](../gradle.properties) para `25` e as imagens base
(`gradle:8.14-jdk21`/`eclipse-temurin:21-jre`) no [`Dockerfile`](../Dockerfile) raiz para 25. O código de virtual threads
é idêntico.

## Ver também
- [10 Observabilidade](10-observabilidade.md) · [13 Testes](13-testes.md)
