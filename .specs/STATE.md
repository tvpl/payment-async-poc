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
- **Phase / Task**: Execute — Phases 1–8 (T1–T53) complete. Phase 9 (workspace closeout, T54–T60) **complete**: T54, T55, T56, T57, T58, T59, T60 all done. This was the last task in the feature's task list — the always-on feature-level Verifier dispatch is next (per the skill's rule, fires automatically after the last task, not on request).
- **Completed**: T1–T60. T54–T59 detail preserved unchanged below. T60 (`e7a6036` "record production readiness evidence"): ran the 6 Java boundaries' Quick+Full gates live against the final post-T59 layout — all green (`payment-contracts` 28/28+28/28 — note: `-PwithIT` is a no-op there, no IT suite exists in that boundary; `payment-api` 68/68+119/119; `payment-sbus` 44/44+76/76; `payment-core-mock` 21/21+27/27; `feature-control` 113/113+141/141; `async-redis-service` 39/39+96/96). First live attempts falsely failed on 3 of 6 boundaries, all traced to this session's own leftover state, not code: port collisions with `:local` containers left running from T59's verification (payment-api :8080, async-redis-service :8084, pilot-app :8085 vs the sandbox's own registry), env vars CI/Compose supply but a bare `./gradlew` invocation doesn't (`PAYMENT_API_KEY`, `JWT_SIGNATURE_SECRET`), and a real awaitility-timeout flake caused by ~11 Testcontainers instances orphaned for 10 hours driving a 12-core machine's load average to 20+. Fixed each root cause (ports freed, env vars exported, orphans removed) and re-ran clean. Reproduced CI's SBOM+Trivy tooling locally (`syft` + `trivy image --severity HIGH,CRITICAL --ignore-unfixed`, same policy as `trivy-action`'s `exit-code: 1`) against all 4 app images — **real FAIL, recorded honestly, not silently promoted to PASS**: 25-31 HIGH/CRITICAL CVEs per image (Netty, Micronaut, Jackson, Kafka clients, Avro, Postgres driver), all with fixes already published upstream; CI's own gate would block every one of these images today. Tracked as a follow-up, `task_40100c4c` (dependency bump + full regression, out of T60's evidence-gathering scope). Reused T59's live `scripts/verify-workspace.sh` run, T57's capacity report, and T59's docs/governance/CI-policy validators — none of that changed. Full report: `validation-evidence/2026-08-12-release-gate.md`; traceability updated in `spec.md` for SEC-08, MIG-03/06/08, EDG-05. All 77 requirements remain at `Execute` status.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: dispatch the feature-level Verifier (author ≠ verifier: spec-anchored outcome check + discrimination sensor in an isolated scratch, writes `.specs/features/repository-segregation-production-hardening/validation.md`, then `<skill-dir>/scripts/validate_state.py` gates completion). This is the closing step of Execute for the whole feature, not a per-task thing — it hasn't run yet for this feature at all. Current docker state: `/sandbox` core + observability infra is up (including the registry, restarted after being briefly stopped during T60's feature-control gate run); the 4 app containers (`payment-api`, `payment-sbus`, `payment-core-mock`, `async-redis-service`) are stopped (not needed after T60's live verification captured its evidence) — bring them back up per whatever the Verifier's discrimination sensor needs (each app's own `docker compose --env-file .env up -d --build`; local `.env` files with dev values already exist per app, gitignored).
- **Blockers:** none. `task_3801253b` (payment-api Redis PubSub deadlock, found by T57) and `task_89c681c8` (correlation-id logging gaps, found by T58) are both **fixed** — see below. Known pre-existing gaps, tracked separately, not blockers: `RedisStatusStore.reserve()` (payment-api) doesn't fail closed on Redis outage — `task_5a0df80c` (reconfirmed live by both T59's and T60's `payment-failures` runs); the SBOM/Trivy dependency-freshness gap found by T60 — `task_40100c4c`. A minor, unrelated pre-existing issue surfaced but not tracked as a task: `scripts/docs/test_validate_docs.py`'s full suite still has 3 stale `broken link ... -> ../.env` failures (docs referencing a gitignored file) — not an observability concern, worth a quick look whenever docs are next touched. Also found and fixed in-passing during T59 (both trivial doc/comment accuracy fixes, not tracked as separate follow-ups): `payment-api/README.md`'s quickstart referenced a nonexistent Gradle task (`publishAllToLocalRepository` → `publishAllToLocalBuildRepository`), and a stale "transitional workspace root" comment in `feature-control/settings.gradle`.
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `feature/plan-continue` (the prior branch `claude/tlc-spec-driven-plan-67avkp` was merged to `main` via PR #20; this branch is 10 commits ahead of `main` — T57's, T58's, the `task_3801253b` fix, the `task_89c681c8` fix, the equivalence-baseline reconciliation, T59's, and T60's).

**`task_89c681c8` fix (outside the T1–T60 sequence, a follow-up to T58's finding, same session):** `SimulationMessageHandler` (`payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java`) now calls `Mdc.fromConsumer(record, env)` before business processing and `Mdc.clear()` in a `finally` — `handle()`'s signature changed to take the whole `ConsumerRecord` instead of separately-extracted `value`+`headers`, which also let `PaymentRequestedConsumer`/`CoreResponseConsumer`/`RetryConsumer` drop their now-redundant header extraction before the call. `ApiPaymentService.publishAndAwait` and `PaymentResponseConsumer.apply` (payment-api) now put `causationId` into MDC. New tests: `MdcUnitTest` (payment-sbus), `ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing`. `./gradlew test -PwithIT` green on both boundaries. Re-ran `scripts/observability/verify.sh` live after rebuilding both images: **12/12 checks pass** (previously 10/12).

**`task_3801253b` fix (outside the T1–T60 sequence, a follow-up to T57's finding, same session):** `ResponseCoordinator.onMessage()` (`payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java`) now dispatches to a virtual thread instead of running `complete()` inline on Lettuce's PubSub event-loop thread — a slow Redis command there used to block delivery of every other instance's completion notifications, not just the current one. New `RedisClientTuning` (`payment-api/src/main/java/com/example/payments/api/redis/RedisClientTuning.java`, `@Context` bean) bounds every Lettuce command to 2s instead of the client's 60s default. Two new regression tests in `ResponseCoordinatorUnitTest` prove a stuck notification for one requestId never delays another's; `RedisClientTuningUnitTest` proves the bounded timeout. `./gradlew test -PwithIT` on payment-api: 118/118 green. Re-ran the exact live reproduction from T57 (167 req/s×90s against `certified-target`) after rebuilding the image: 0 `RedisCommandTimeoutException` in the container logs (previously recurring every ~60s), 0 technical errors (previously 40-44%).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
