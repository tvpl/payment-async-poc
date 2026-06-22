# 12 — Execução e operação

## Pré-requisitos
- **Docker** + Docker Compose (para o stack completo).
- **JDK 21** (alvo atual; ver caminho para 25 abaixo) e o wrapper `./gradlew` (build local).
- Opcional: **k6** (teste de carga).

## Subir o stack completo

```bash
docker compose up -d --build
docker compose ps   # aguarde healthchecks (kafka, postgres, redis, apicurio) + kafka-init
```

### Portas / URLs

| Serviço | URL |
|---|---|
| API | http://localhost:8080 |
| API — OpenAPI | http://localhost:8080/swagger/payment-simulation-api-1.0.yml |
| SBUS | http://localhost:8081 |
| core-mock | http://localhost:8082 |
| Apicurio Schema Registry | http://localhost:8085 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Jaeger (traces) | http://localhost:16686 |
| Métricas | `:8080/prometheus`, `:8081/prometheus`, `:8082/prometheus` |
| Kafka (host) | `localhost:29092` |

## Exemplos curl

```bash
# Simulação (200 se o Core responder no prazo, ou 202)
curl -i -X POST http://localhost:8080/payment-simulations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-123' \
  -d '{"merchantId":"MERCHANT-001","amount":125.50,"currency":"BRL",
       "paymentMethod":"CREDIT_CARD","brand":"VISA","installments":3,
       "captureMode":"AUTHORIZE_AND_CAPTURE"}'

# Status por requestId
curl -i http://localhost:8080/payment-simulations/<requestId>

# Idempotência: repetir o POST com a MESMA Idempotency-Key reusa o requestId original
```

## Teste de carga (k6)

```bash
k6 run -e BASE_URL=http://localhost:8080 -e RATE=300 -e DURATION=1m load/k6-simulations.js
```
Espere uma mistura de `200/202` e alguns `429` (rate limit) sob taxa alta. Ver
[`load/k6-simulations.js`](../load/k6-simulations.js).

## Desenvolvimento local (sem compose para os apps)

Kafka exposto em `localhost:29092`; use o profile `dev` para desligar o export OTLP:

```bash
docker compose up -d kafka kafka-init redis postgres apicurio-registry otel-collector jaeger prometheus grafana
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

## Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| App não sobe por erro OTLP | Collector ausente em dev | `MICRONAUT_ENVIRONMENTS=dev` (desliga export) |
| `GET` fica em `SENT_TO_SBUS` | Evento final ainda não processado | Aguardar; o `GET` cai para o SBUS (durável) |
| Muitos `429` | Rate limit de admissão | Ajustar `payment.simulation.rate-limit.*` |
| `outbox_event` crescendo | Kafka indisponível / publish falhando | Ver `sbus_outbox_pending`, logs do dispatcher |
| Mensagens na DLQ | Poison/erro permanente | Inspecionar `payment.simulation.dlq` (headers `x-dlq-*`) |
| `Could not find a valid Docker environment` (testes) | Sem Docker | Rodar só unit: `--tests '*UnitTest'` |

## Subir para Java 25
Trocar `javaLanguageVersion` em [`gradle.properties`](../gradle.properties) para `25` e as imagens base
(`gradle:8.14-jdk21`/`eclipse-temurin:21-jre`) nos `*/Dockerfile` para 25. O código de virtual threads
é idêntico.

## Ver também
- [10 Observabilidade](10-observabilidade.md) · [13 Testes](13-testes.md)
