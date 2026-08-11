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
| `async-redis-failures` | matriz de falhas multi-instância do async-redis (RED-01..08) — instâncias/scratch containers reais, 10 cenários | `async-redis-failures/run.sh` |
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

## `async-redis-failures` (T56)

Sobe uma segunda instância real de `async-redis-service` (`async-redis-failures/multi_instance.sh`,
mesmo padrão `docker run` do T55) para o cenário de identidade/reclaim cross-instance; os outros
nove cenários sobem seus próprios containers scratch efêmeros com configuração específica —
latência, pool, TTL, `max-deliveries`, `fail-on-reference`, `MICRONAUT_ENVIRONMENTS=prod` — porque
os 8 requisitos RED exigem ajustes mutuamente incompatíveis (ex.: TTL curto para expiração vs. TTL
de produção para o resto). 10 cenários, 34 asserções: ciclo de vida do status
(missing/processing/terminal/expired), backpressure do pool de espera, alerta de retenção sem
auto-trim, identidade única de consumer + dono único do reclaim entre 2 instâncias, readiness cai
e recupera numa queda real do Redis, liberação atômica sobrevive a um roubo de posse do PEL em
pleno voo (`XCLAIM` para um consumer forjado), DLQ antes do ACK (mensagem malformada e
excesso de entregas), limite de admissão compartilhado via Redis entre 2 instâncias (não é um
contador local em memória), e o `ProductionAcceptanceGuard` recusando startup em `prod` para cada
gate individualmente (chave dev, idempotência desligada, admissão zerada) mais um controle
positivo. Cada container scratch usa seu próprio nome de *consumer group*, não só de stream — a
lease do reclaim é `reclaim:{group}:owner`, chaveada só pelo nome do grupo, então containers que
dividissem o grupo padrão `workers` com a frota principal já rodando ficariam sem vez no scan de
reclaim (só um dono por grupo de cada vez). Nunca acontece em produção, onde uma frota real
deliberadamente compartilha um grupo para uma stream; só afeta um harness de teste que sobe
implantações logicamente separadas lado a lado. Roda em ~2-3 minutos.
