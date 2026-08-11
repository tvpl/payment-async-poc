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
- **Phase / Task**: Execute in progress — Phases 1–8 (T1–T53) complete. Phase 9 (workspace closeout, T54–T60) underway: **T54 and T55 complete**, **T56 next** — reconciled 2026-08-10 against `tasks.md` (T1–T55 carry `Status: Complete`) and `git log`, since the previous Handoff entry (below) was written before T54/T55's commits landed and incorrectly claimed Phase 9 hadn't started.
- **Completed**: T1–T55. T54 (`9084b86` "prove artifact-only flow equivalence"): published `payment-contracts`/`feature-control` to local Maven repos, built all four consumer images from those repos with no sibling source, proved no composite build (`scripts/e2e/check_no_composite_build.py`), regenerated the equivalence baseline (44 relocated files reconciled, `equivalence: PASS (389 entries)`), and ran 2 real E2E flows (Kafka payment, Redis async) via `scripts/verify-workspace.sh`. T55 (`1a69dee` "add multiinstance failure matrix"): built `scripts/e2e/payment-failures/`, an 11-scenario live harness against 2 real `payment-api`+`payment-sbus` instances; 10/11 pass — the one documented failure is a real gap (`RedisStatusStore.reserve()` leaks an unhandled 500 instead of failing closed per PAY-09) flagged as follow-up task `task_5a0df80c`, not fixed in-task per SPEC_DEVIATION note in `tasks.md`. Detail on T1–T53 is preserved in the prior Handoff entries below, unchanged.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: T56 — async-redis multi-instance failure matrix (RED-01..08, `scripts/e2e/async-redis-failures`, mirrors T55's structure), then T57 (capacity gate) → T58 (observability) → T59 (remove verified legacy layout) → T60 (release gate + final evidence). `/sandbox` infra is currently up (`docker ps` shows kafka/redis/postgres/registry + all 4 app containers healthy) — no need to bring it up again unless it was stopped since. After T60, dispatch the feature-level Verifier per the skill's always-on rule.
- **Blockers:** none. Known pre-existing gap (not a T56 blocker): `RedisStatusStore.reserve()` (payment-api) doesn't fail closed on Redis outage — tracked separately, unrelated to async-redis-service.
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `claude/tlc-spec-driven-plan-67avkp` (15 commits ahead of `feature/optimize-eda`, which itself was already merged to `main` via PR #4; this branch carries the Phase 7 tail + all of Phase 8 + T54/T55 not yet on `main`).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
