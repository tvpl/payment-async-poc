# Gate cross-boundary (Fase 9)

Prova que o workspace funciona a partir de artefatos publicados, sem build source-level
compartilhado, usando somente os Composes independentes de cada fronteira. Ponto de entrada:
`scripts/verify-workspace.sh` (design.md §8.2).

## Pré-requisitos

```bash
cd sandbox && docker compose up -d   # Kafka, Redis, PostgreSQL, Registry
cd payment-contracts && ./gradlew publishAllToLocalBuildRepository --no-daemon
./gradlew :feature-control:publishMavenPublicationToLocalBuildRepository --no-daemon   # a partir de feature-control/
for d in payment-api payment-sbus payment-core-mock async-redis-service; do
  (cd "$d" && docker compose build && docker compose up -d)
done
```

## Estágios

| Estágio | O que prova | Arquivo |
| --- | --- | --- |
| `equivalence` | inventário não perde item válido (ORG-08) | `scripts/equivalence/equivalence.py` |
| `no-composite-build` | os quatro consumidores reais resolvem `payment-contracts`/`feature-control` por GAV, não por `includeBuild`/`project(...)`/fonte irmã (MIG-05) | `check_no_composite_build.py` |
| `artifact-only-fixture` | mecanismo genérico: GAV publicado resolve, GAV ausente falha (T1/T6) | `scripts/artifacts/verify-artifact-only.sh` |
| `e2e-payment` | fluxo completo API -> Kafka -> SBUS -> core-mock | `scripts/smoke.sh` |
| `e2e-async-redis` | fluxo completo submit -> Redis Stream -> worker -> BRPOP | `async_redis_smoke.sh` |
| `payment-failures` | matriz de falhas multi-instância do pagamento (PAY-05..09, CAP-05, CAP-06) — 2 instâncias reais de API/SBUS, ≥10 cenários | `payment-failures/run.sh` |
| `hygiene` | `git diff --check` | - |

Cada tarefa da Fase 9 acrescenta um estágio a este mesmo script em vez de substituí-lo; T60
registra a evidência de todos eles.

## `payment-failures` (T55)

Sobe uma segunda instância real de `payment-api`/`payment-sbus` (`payment-failures/multi_instance.sh`,
via `docker run`, não `--scale` — os Composes fixam porta de host) e roda 11 cenários live contra
o sandbox: idempotência/coordenação cross-instance, janela de crash do outbox (lease reclaim),
kill de container mid-flight, retry due-based não bloqueando tráfego vivo, backpressure com Core
lento, mensagem poison para DLQ, e Kafka/Redis/PostgreSQL/Registry indisponíveis um de cada vez.
Precisa de `POSTGRES_PASSWORD` (valor de `sandbox/.env`); `verify-workspace.sh` já lê esse arquivo
sozinho. Roda em ~8-10 minutos por causa dos timeouts reais de produção (Kafka
`delivery.timeout.ms` ≈ 2min, lease do outbox + reaper ≈ 90s).
