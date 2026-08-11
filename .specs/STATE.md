# STATE

## Decisions

### AD-001 — Fronteiras do workspace

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** o workspace terá sete raízes autossuficientes: `payment-contracts`, `payment-api`, `payment-sbus`, `payment-core-mock`, `feature-control`, `async-redis-service` e `sandbox`; exemplos ficam dentro de `feature-control`.
- **Rationale:** separa ownership, release e documentação sem promover demonstrações a produtos.

### AD-002 — Dependências cross-boundary

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** contratos e bibliotecas serão consumidos por coordenadas Maven versionadas; composite build será somente uma conveniência local explícita e os gates de release testarão artefatos publicados.
- **Rationale:** reproduz repositórios independentes e impede acoplamento invisível ao source tree.

### AD-003 — Ownership da infraestrutura local

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** somente `/sandbox` criará Kafka, Redis, PostgreSQL, Registry e observabilidade; aplicações usarão Composes próprios conectados a uma rede externa do sandbox.
- **Rationale:** evita infraestrutura duplicada e permite iniciar/escalar aplicações de forma independente.

### AD-004 — Estratégia de migração

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** a migração será incremental e orientada a artefatos, com equivalência e rollback por fronteira; repositórios remotos serão criados somente em iniciativa posterior.
- **Rationale:** reduz blast radius e torna regressões atribuíveis.

### AD-005 — Critério de produção

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** `payment-api`, `payment-sbus`, `payment-contracts`, `feature-control` e `async-redis-service` terão gates produtivos; `payment-core-mock`, `feature-demo` e `pilot-app` serão explicitamente `NON_PRODUCTION`. Nenhum claim de prontidão vale sem relatório datado.
- **Rationale:** separa evidência operacional de finalidade didática.

### AD-006 — Meta de capacidade e saturação

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** o gate alvo será 10.000 req/min por 15 minutos e spike de 20.000 req/min por 60 segundos; excesso terá `429`, `202` ou buffering limitado, nunca perda silenciosa aceita.
- **Rationale:** transforma “milhares por minuto” em capacidade mensurável e trata limites downstream como restrição real.

## Handoff

- **Feature**: `repository-segregation-production-hardening`
- **Phase / Task**: Execute in progress — Phases 1–8 (T1–T53) complete. Phase 9 (workspace closeout, T54–T60) underway: **T54, T55, T56, T57, T58 complete**, **T59 next**.
- **Completed**: T1–T58. T54–T57 detail preserved unchanged below. T58 (`348ec94` "validate owned signals and dashboards"): built `scripts/observability/` (bring-up via each app's own `docker compose`, `verify.sh` + 6 `checks/*.sh`) and ran it live against a single-instance fleet of every app. Activated SBX-05's previously-empty `sandbox/observability/application-assets.json`/`application-targets.json` with real owner/version/path declarations and scrape targets, mounted (not copied) into the sandbox's Prometheus (`sandbox/compose.profiles.yml`, `rule_files` in `sandbox/observability/prometheus.yml`). Result: **10/12 checks pass** (floor: 8). The 2 failures are real, confirmed product gaps, not harness bugs: `payment-sbus`'s `Mdc.java` (structured correlation logging) is never called anywhere in the codebase (`grep -rn "Mdc\." payment-sbus/src/` finds nothing), and `payment-api` never populates `causationId` despite declaring it in `logback.xml`. Tracked together as **`task_89c681c8`** (chip spawned), not fixed in-task (out of `scripts/observability`'s scope, same boundary T55/T57 drew for their own findings). Also fixed a real, pre-existing bug in T5's own dashboard-metric validator (`scripts/docs/validate_docs.py#executable_corpus`): its glob missed `feature-control`'s nested `library`/`examples` source depth, silently letting `feature_decisions_total` go unverified — confirmed already failing on the commit before this change touched anything. Full narrative: `tasks.md` T58 Gate evidence + `scripts/observability/verify-evidence.txt`.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: T59 — relocate valid content and remove obsolete legacy layout (DOC-05/06/07, MIG-01/02/07/08; `/`). This is the task that finally moves dashboards out of the legacy root `observability/grafana/dashboards/` into each app's own directory (T58 validated them in place, didn't relocate them — out of its own scope). Then T60 (release gate + final evidence). `/sandbox` core infra (kafka/redis/postgres/registry) plus the observability profile (prometheus/grafana/jaeger/otel-collector/exporters) are up; the app tier (`payment-api-api-1`, `payment-sbus-sbus-1`, `payment-core-mock-core-mock-1`) was torn down at the end of T58 — bring it back up per whatever T59 needs (each app's own `docker compose up -d --wait`, see `scripts/observability/lib.sh#bring_up` for the exact env vars needed: `PAYMENT_API_KEY`, `JWT_SIGNATURE_SECRET`, `SBUS_DEV_JWT_SECRET`, `POSTGRES_PASSWORD`). After T60, dispatch the feature-level Verifier per the skill's always-on rule.
- **Blockers:** none for T59. `task_3801253b` (payment-api Redis PubSub deadlock, found by T57) and `task_89c681c8` (correlation-id logging gaps, found by T58) are both **fixed** — see below. One remaining known pre-existing gap (not a blocker, tracked separately): `RedisStatusStore.reserve()` (payment-api) doesn't fail closed on Redis outage — `task_5a0df80c`. A minor, unrelated pre-existing issue surfaced but not tracked as a task: `scripts/docs/test_validate_docs.py`'s full suite still has 3 stale `broken link ... -> ../.env` failures (docs referencing a gitignored file) — not an observability concern, worth a quick look whenever docs are next touched.
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `feature/plan-continue` (the prior branch `claude/tlc-spec-driven-plan-67avkp` was merged to `main` via PR #20; this branch is 4 commits ahead of `main` — T57's, T58's, the `task_3801253b` fix, and the `task_89c681c8` fix).

**`task_89c681c8` fix (outside the T1–T60 sequence, a follow-up to T58's finding, same session):** `SimulationMessageHandler` (`payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java`) now calls `Mdc.fromConsumer(record, env)` before business processing and `Mdc.clear()` in a `finally` — `handle()`'s signature changed to take the whole `ConsumerRecord` instead of separately-extracted `value`+`headers`, which also let `PaymentRequestedConsumer`/`CoreResponseConsumer`/`RetryConsumer` drop their now-redundant header extraction before the call. `ApiPaymentService.publishAndAwait` and `PaymentResponseConsumer.apply` (payment-api) now put `causationId` into MDC. New tests: `MdcUnitTest` (payment-sbus), `ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing`. `./gradlew test -PwithIT` green on both boundaries. Re-ran `scripts/observability/verify.sh` live after rebuilding both images: **12/12 checks pass** (previously 10/12).

**`task_3801253b` fix (outside the T1–T60 sequence, a follow-up to T57's finding, same session):** `ResponseCoordinator.onMessage()` (`payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java`) now dispatches to a virtual thread instead of running `complete()` inline on Lettuce's PubSub event-loop thread — a slow Redis command there used to block delivery of every other instance's completion notifications, not just the current one. New `RedisClientTuning` (`payment-api/src/main/java/com/example/payments/api/redis/RedisClientTuning.java`, `@Context` bean) bounds every Lettuce command to 2s instead of the client's 60s default. Two new regression tests in `ResponseCoordinatorUnitTest` prove a stuck notification for one requestId never delays another's; `RedisClientTuningUnitTest` proves the bounded timeout. `./gradlew test -PwithIT` on payment-api: 118/118 green. Re-ran the exact live reproduction from T57 (167 req/s×90s against `certified-target`) after rebuilding the image: 0 `RedisCommandTimeoutException` in the container logs (previously recurring every ~60s), 0 technical errors (previously 40-44%).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
