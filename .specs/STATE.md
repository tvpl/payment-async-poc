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
- **Phase / Task**: Execute in progress — Phases 1–8 (T1–T53) complete. Phase 9 (workspace closeout, T54–T60) underway: **T54, T55, T56, T57 complete**, **T58 next**.
- **Completed**: T1–T57. T54–T56 detail preserved unchanged below. T57 (`434f20f` "add reproducible capacity gate"): built `load/capacity/` (manifest, k6 orchestration via `run_gate.sh`, sampled-reconciliation, `generate_report.py` with a deterministic `selftest` proving CAP-07's fail path) and `load/k6/capacity.js`, then ran it live — both `certified-target` and `constrained-core` profiles, all five scenarios (steady 167 req/s×15m, spike 333 req/s×60s per AD-006's literal numbers, plus soak/slowdown/recovery). Verdict: **FAIL** on `certified-target` — root-caused (not a harness bug) to a real `payment-api` gap: `ResponseCoordinator.complete()` (`payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java:119`) calls `RedisStatusStore.get()` (`.../redis/RedisStatusStore.java:73`) synchronously from inside the Lettuce PubSub callback thread; that store's single unpooled Redis connection backs up under sustained load until Lettuce's default 60s command timeout fires, stalling completion notifications on the affected instance (evidence: `load/reports/certified-target/cpucheck.redis-timeout-evidence.txt`, recurring ~60s apart). Ruled out test-harness causes first (VU/CPU starvation) before accepting this as the real cause — doubling container CPU (1.0→2.0) reproduced the identical failure. Not fixed in-task (out of `load/`'s scope, same boundary T55 drew for its own `RedisStatusStore` finding) — tracked as follow-up **`task_3801253b`** (also spawned as a background-task chip, id `task_1407b9ca`). Full narrative and root-cause diagnosis: `tasks.md` T57 Gate evidence + `load/reports/20260811-185714-capacity-report.md`.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: T58 — validate observability and dashboard references (SBX-05, DOC-03, EDG-04; `scripts/observability`). Note: `sandbox/observability/application-targets.json` is still `[]` (apps not yet registered for Prometheus scrape) — this is T58's job to wire, not a T57 regression. Then T59 (remove verified legacy layout) → T60 (release gate + final evidence). `/sandbox` infra is up (kafka/redis/postgres/registry healthy) but the app tier (`payment-api-1/2`, `payment-sbus-1/2`, `payment-core-mock`) was torn down at the end of T57 — bring it back up per whatever T58 needs. After T60, dispatch the feature-level Verifier per the skill's always-on rule.
- **Blockers:** none for T58. Two known pre-existing gaps (not blockers, tracked separately): `RedisStatusStore.reserve()` (payment-api) doesn't fail closed on Redis outage — `task_5a0df80c`; `RedisStatusStore`'s unpooled/no-timeout connection causes request hangs under sustained load — `task_3801253b` (chip `task_1407b9ca`).
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `feature/plan-continue` (the prior branch `claude/tlc-spec-driven-plan-67avkp` was merged to `main` via PR #20; this branch is 1 commit ahead of `main` — T57's commit).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
