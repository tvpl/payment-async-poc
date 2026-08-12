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
- **Phase / Task**: Execute in progress — Phases 1–8 (T1–T53) complete. Phase 9 (workspace closeout, T54–T60) underway: **T54, T55, T56, T57, T58, T59 complete**, **T60 next**.
- **Completed**: T1–T59. T54–T58 detail preserved unchanged below. T59 (`add6749` "remove verified legacy layout", 208 files changed): scope turned out far larger than the task description implied — 244 pending doc sections (not ~7), a red equivalence gate (two stale globs), CI still hardcoded to legacy paths, and 10 unrelocated dashboards. Ran the full task with parallel sub-agents (one per doc-migration owner boundary): migrated all 251 tracked sections in `scripts/docs/relocation-manifest.json` to `MIGRATED`, relocated the 10 Grafana dashboards via `git mv` into each owner's `ops/dashboards/` (renaming two whose old names didn't match their real metrics: `redis-overview.json` → `payment-api/ops/dashboards/api-waiters.json`, `postgres-overview.json` → `payment-sbus/ops/dashboards/postgres-pool.json`), rewrote `.github/workflows/ci.yml`/`dependabot.yml` for the 7 standalone roots, rewrote root `README.md`/`AGENTS.md` without "transitional" framing, and deleted `common/`, `api-service/`, `sbus-service/`, `core-mock/`, `observability/`, plus the root Gradle/Docker/Compose files — only after `equivalence.py verify` passed at 409 entries against the standalone layout alone. Fixed 4 validator scripts that hardcoded legacy paths and would have broken or given false results after the deletion: `equivalence.py`'s dashboards glob, `build_relocation_manifest.py` (crashed on a missing source), `validate_docs.py#manifest_errors` (needed a terminal-state branch instead of literally reporting all 251 sections stale), and `known_ports()`/`known_variables()` (read the deleted root `docker-compose.yml`). Also fixed `scripts/artifacts/verify-artifact-only.sh`, found broken only when running the full gate live: it invoked the now-deleted root `gradlew` via `-p <dir>` for two out-of-tree Gradle calls; repointed both at `feature-control`'s own wrapper (borrowed as a generic launcher for the `consumer-fixture` project, same mechanism the script already used). Full live run of `scripts/verify-workspace.sh` (all 8 stages, apps rebuilt and brought up fresh via each one's own `docker compose --env-file .env up --build` against freshly published `payment-contracts`/`feature-control` artifacts): equivalence PASS (409), no-composite-build PASS (4 boundaries), artifact-only-consumer PASS, e2e-payment `SMOKE OK (COMPLETED)`, e2e-async-redis `SMOKE OK (COMPLETED)`, payment-failures PASS (10/11, floor 10 — the one failure, `redis-unavailable-api`, is the same pre-existing tracked gap as `task_5a0df80c` below, not new), async-redis-failures PASS (34/34), hygiene PASS. Full narrative: `tasks.md` T59 Gate evidence; traceability updated in `spec.md` for DOC-05/06/07, MIG-01/02/07/08.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: T60 — run the full release gate and persist dated per-boundary evidence (SEC-08, MIG-03/04/05/06/08, CAP-04/07, EDG-05; `.specs/features/repository-segregation-production-hardening/validation-evidence`). After T60, dispatch the feature-level Verifier per the skill's always-on rule — this is the LAST task in the feature, so the Verifier fires automatically once T60's commit lands. The app tier (`payment-api-api-1`, `payment-sbus-sbus-1`, `payment-core-mock-core-mock-1`, `async-redis-service-async-redis-1`) is currently up and healthy from T59's live verification, built from local `.env` files (gitignored, not committed) with dev-only values — reuse them for T60 rather than recreating (`PAYMENT_API_KEY=dev-key-change-me`, `POSTGRES_PASSWORD` matches `sandbox/.env`'s value, JWT/async-redis secrets are the `.env.example` dev defaults). `/sandbox` core + observability infra is up.
- **Blockers:** none for T60. `task_3801253b` (payment-api Redis PubSub deadlock, found by T57) and `task_89c681c8` (correlation-id logging gaps, found by T58) are both **fixed** — see below. One remaining known pre-existing gap (not a blocker, tracked separately): `RedisStatusStore.reserve()` (payment-api) doesn't fail closed on Redis outage — `task_5a0df80c`; T59's live `payment-failures` run reconfirmed it (`redis-unavailable-api` still returns a leaking 500 instead of a fail-closed 503/429). A minor, unrelated pre-existing issue surfaced but not tracked as a task: `scripts/docs/test_validate_docs.py`'s full suite still has 3 stale `broken link ... -> ../.env` failures (docs referencing a gitignored file) — not an observability concern, worth a quick look whenever docs are next touched. Also found and fixed in-passing during T59 (not tracked as separate follow-ups, both trivial doc/comment accuracy fixes): `payment-api/README.md`'s quickstart referenced a nonexistent Gradle task (`publishAllToLocalRepository` → `publishAllToLocalBuildRepository`), and a stale "transitional workspace root" comment in `feature-control/settings.gradle`.
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `feature/plan-continue` (the prior branch `claude/tlc-spec-driven-plan-67avkp` was merged to `main` via PR #20; this branch is 8 commits ahead of `main` — T57's, T58's, the `task_3801253b` fix, the `task_89c681c8` fix, the equivalence-baseline reconciliation, and T59's).

**`task_89c681c8` fix (outside the T1–T60 sequence, a follow-up to T58's finding, same session):** `SimulationMessageHandler` (`payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java`) now calls `Mdc.fromConsumer(record, env)` before business processing and `Mdc.clear()` in a `finally` — `handle()`'s signature changed to take the whole `ConsumerRecord` instead of separately-extracted `value`+`headers`, which also let `PaymentRequestedConsumer`/`CoreResponseConsumer`/`RetryConsumer` drop their now-redundant header extraction before the call. `ApiPaymentService.publishAndAwait` and `PaymentResponseConsumer.apply` (payment-api) now put `causationId` into MDC. New tests: `MdcUnitTest` (payment-sbus), `ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing`. `./gradlew test -PwithIT` green on both boundaries. Re-ran `scripts/observability/verify.sh` live after rebuilding both images: **12/12 checks pass** (previously 10/12).

**`task_3801253b` fix (outside the T1–T60 sequence, a follow-up to T57's finding, same session):** `ResponseCoordinator.onMessage()` (`payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java`) now dispatches to a virtual thread instead of running `complete()` inline on Lettuce's PubSub event-loop thread — a slow Redis command there used to block delivery of every other instance's completion notifications, not just the current one. New `RedisClientTuning` (`payment-api/src/main/java/com/example/payments/api/redis/RedisClientTuning.java`, `@Context` bean) bounds every Lettuce command to 2s instead of the client's 60s default. Two new regression tests in `ResponseCoordinatorUnitTest` prove a stuck notification for one requestId never delays another's; `RedisClientTuningUnitTest` proves the bounded timeout. `./gradlew test -PwithIT` on payment-api: 118/118 green. Re-ran the exact live reproduction from T57 (167 req/s×90s against `certified-target`) after rebuilding the image: 0 `RedisCommandTimeoutException` in the container logs (previously recurring every ~60s), 0 technical errors (previously 40-44%).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
