# Residual Resilience Findings Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is the source of truth for the full flow (per-task cycle, sub-agent delegation, adequacy review, Verifier, discrimination sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Spec**: `.specs/features/residual-resilience-findings/spec.md`
**Design**: none — nenhuma decisão arquitetural nova; o alvo já está documentado em `design.md` §7.2 de `repository-segregation-production-hardening` e os padrões (`PublishFailedException` + handler, `AdmissionRedisOutageIT`) já existem no boundary.
**Status**: In Progress

---

## Test Coverage Matrix

> Gerado do codebase, das guidelines do projeto e do spec — confirmar antes do Execute. Guidelines encontradas: `payment-api/AGENTS.md` (§Gates, §"os testes em `src/test/java` são a especificação executável", "Não enfraqueça, pule ou remova teste para fazer um gate passar"), `payment-api/build.gradle` (`useJUnitPlatform`, filtro `*IT` sob `-PwithIT`), `payment-api/docs/testing.md`.

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Error / exception (POJO) | none | build gate apenas | `src/main/java/**/error/*Exception.java` | build gate |
| Error handler (mapeia exceção → HTTP) | unit | Todos os ramos; 1:1 com as ACs de status/corpo | `src/test/java/**/error/*UnitTest.java` | `./gradlew test --no-daemon` |
| Redis store (data-access) | integration | Caminhos de query principais + caminho de erro (Redis fora) | `src/test/java/**/redis/*IT.java` | `./gradlew test -PwithIT --no-daemon` |
| Service (domain) | unit | Todos os ramos; 1:1 com as ACs do spec | `src/test/java/**/service/*UnitTest.java` | `./gradlew test --no-daemon` |
| Kafka consumer | unit | Caminho de sucesso + caminho de falha de escrita | `src/test/java/**/kafka/*UnitTest.java` | `./gradlew test --no-daemon` |
| Controller / rota (e2e) | integration | Toda rota no escopo: happy + edge + erro | `src/test/java/**/*IT.java` | `./gradlew test -PwithIT --no-daemon` |
| Script de gate (bash) | none | Verificado pela própria execução ao vivo do gate | `scripts/e2e/payment-failures/scenarios/*.sh` | `scripts/verify-workspace.sh payment-failures` |

## Gate Check Commands

> Gerado do codebase — confirmar antes do Execute. `PAYMENT_API_KEY` e `JWT_SIGNATURE_SECRET` são obrigatórios: `application.yml` referencia `${PAYMENT_API_KEY}` sem default e o compose usa `:?`, então a ausência quebra o boot (comprovado nesta sessão: `ApiSecurityIT` falhou com `Could not resolve placeholder ${PAYMENT_API_KEY}`).

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Após tarefas só com teste unitário | `cd payment-api && ./gradlew test --no-daemon` |
| Full | Após tarefas com IT | `cd payment-api && PAYMENT_API_KEY=local-test-key-not-a-real-secret JWT_SIGNATURE_SECRET=test-only-api-signing-secret-with-at-least-32-bytes ./gradlew test -PwithIT --no-daemon` |
| Build | Fim de fase, ou tarefas só de config/POJO | `cd payment-api && ./gradlew build -x test --no-daemon` + `python3 scripts/equivalence/equivalence.py verify --manifest scripts/equivalence/baseline-manifest.json` + `git diff --check` |
| Gate ao vivo | Fase 3 apenas | `scripts/verify-workspace.sh payment-failures` (exige stack de pé) |

---

## Execution Plan

Fases são ordenadas e rodam em sequência — cada fase termina antes da próxima, e as tarefas dentro de uma fase rodam em ordem.

### Phase 1: Falha fechada no `payment-api`

A exceção (T1) destrava o handler e o store em paralelo lógico; o IT (T6) só fecha depois que os três caminhos de produção existem. A execução continua sequencial (T1→T2→T3→T4→T5→T6); as setas abaixo são a dependência real, não a ordem de digitação.

```
T1 → T2
T1 → T3
T3 → T4
T3 → T5
T2 → T6
T4 → T6
T5 → T6
```

### Phase 2: Determinismo do cenário de gate

Independente da Phase 1 — defeito de teste, não de produto.

```
T7
```

### Phase 3: Evidência ao vivo

```
T6 → T8
T7 → T8
```

---

## Task Breakdown

### T1: Criar `StoreUnavailableException` ✅

**What**: Exceção tipada para "o store compartilhado (Redis) não respondeu", carregando a causa original sem expô-la.
**Where**: `payment-api/src/main/java/com/example/payments/api/error/StoreUnavailableException.java`
**Depends on**: None
**Reuses**: `payment-api/src/main/java/com/example/payments/api/error/PublishFailedException.java` (mesma forma e propósito)
**Requirement**: RES-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Estende `RuntimeException`, aceita `(String operation, Throwable cause)`
- [ ] Javadoc explica por que a causa nunca vai para o corpo HTTP
- [ ] Gate check passa: `cd payment-api && ./gradlew build -x test --no-daemon`

**Tests**: none
**Gate**: build

**Commit**: `feat(payment-api): add a typed store-unavailable exception`

---

### T2: Criar `StoreUnavailableExceptionHandler` ✅

**What**: Handler que mapeia `StoreUnavailableException` para `503` + `application/problem+json`, com `Retry-After`, sem texto de infraestrutura no corpo.
**Where**: `payment-api/src/main/java/com/example/payments/api/error/StoreUnavailableExceptionHandler.java`
**Depends on**: T1
**Reuses**: `payment-api/src/main/java/com/example/payments/api/error/PublishFailedExceptionHandler.java`, `Problem.of(...)`
**Requirement**: RES-01, RES-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Retorna `503` com `Problem.MEDIA_TYPE` e header `Retry-After: 1`
- [ ] A causa vai para o log (ERROR), nunca para o corpo
- [ ] Teste unitário prova: status `503`, e que o corpo serializado não contém `redis`, o host nem a porta (asserção sobre a string, não sobre o formato)
- [ ] Gate check passa: `cd payment-api && ./gradlew test --no-daemon`
- [ ] Test count: 121 + 2 novos = 123 testes passam (sem deleção silenciosa)

**Tests**: unit
**Gate**: quick

**Commit**: `feat(payment-api): map store unavailability to 503 without leaking internals`

---

### T3: Blindar `RedisStatusStore` contra falha do Lettuce

**What**: Envolver as 7 chamadas Lettuce (`save`, `get`, `reserve` ×2, `markPublishState`, `publishResponse`, `commands()`) para lançar `StoreUnavailableException` em vez de deixar a exceção do driver escapar.
**Where**: `payment-api/src/main/java/com/example/payments/api/redis/RedisStatusStore.java`
**Depends on**: T1
**Reuses**: `payment-api/src/test/java/com/example/payments/api/redis/RedisStatusStoreIdempotencyIT.java` (harness), `AdmissionRedisOutageIT` (parada real do Redis)
**Requirement**: RES-01, RES-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Nenhuma exceção de Lettuce escapa de método público algum da classe
- [ ] `get()` continua distinguindo "chave ausente" (`Optional.empty()`) de "store indisponível" (exceção) — não colapsar os dois
- [ ] IT com Redis realmente parado prova que cada método público lança `StoreUnavailableException`, não `RedisConnectionException`
- [ ] Gate check passa: gate Full
- [ ] Test count: 123 + 2 novos = 125 testes passam

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-api): fail closed instead of leaking Lettuce failures`

---

### T4: Preservar o fallback durável do SBUS no `getStatus`

**What**: Fazer `ApiPaymentService.getStatus` tratar a indisponibilidade do Redis como "não sei ainda" e seguir para o fallback do SBUS, propagando `StoreUnavailableException` só quando nenhum dos dois responde.
**Where**: `payment-api/src/main/java/com/example/payments/api/service/ApiPaymentService.java`
**Depends on**: T3
**Reuses**: `fromSbus(...)` e `sbusStatusGateway` já existentes no mesmo arquivo
**Requirement**: RES-02, RES-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Redis fora + SBUS respondendo → devolve o status durável (nunca `503`)
- [ ] Redis fora + SBUS sem resposta → propaga `StoreUnavailableException` (vira `503`), nunca `Optional.empty()` que viraria `404`
- [ ] Redis de pé → comportamento inalterado
- [ ] Teste unitário cobre os três ramos acima
- [ ] Gate check passa: `cd payment-api && ./gradlew test --no-daemon`
- [ ] Test count: 125 + 3 novos = 128 testes passam

**Tests**: unit
**Gate**: quick

**Commit**: `fix(payment-api): keep the durable status fallback when Redis is down`

---

### T5: Impedir que o consumer confirme offset após falha de escrita

**What**: Garantir que `PaymentResponseConsumer` propague `StoreUnavailableException` em vez de engolir, para que o offset não avance e o resultado do pagamento não se perca.
**Where**: `payment-api/src/main/java/com/example/payments/api/kafka/PaymentResponseConsumer.java`
**Depends on**: T3
**Reuses**: padrão de rethrow de `payment-sbus/src/main/java/.../kafka/RetryPublisher.java`
**Requirement**: RES-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Falha de escrita do status propaga (não há `catch` que engula)
- [ ] Teste unitário prova que a exceção sai do handler do listener
- [ ] Teste unitário prova que o caminho de sucesso continua não lançando
- [ ] Gate check passa: `cd payment-api && ./gradlew test --no-daemon`
- [ ] Test count: 128 + 2 novos = 130 testes passam

**Tests**: unit
**Gate**: quick

**Commit**: `fix(payment-api): never ack a response whose status write failed`

---

### T6: IT ponta a ponta com Redis realmente parado

**What**: IT que para o container de Redis e assere `POST → 503`, `GET → 503` (sem SBUS) e recuperação sem reinício, incluindo a ausência de tokens de infraestrutura no corpo.
**Where**: `payment-api/src/test/java/com/example/payments/api/RedisOutageFailClosedIT.java`
**Depends on**: T2, T4, T5
**Reuses**: `payment-api/src/test/java/com/example/payments/api/AdmissionRedisOutageIT.java` (para/religa o Redis de verdade)
**Requirement**: RES-01, RES-03, RES-04, RES-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `POST` com Redis parado → `503`, corpo sem `redis`/host/porta
- [ ] `GET` com Redis parado e sem fallback → `503`, nunca `404`
- [ ] Após religar o Redis, um novo `POST` é aceito sem reiniciar a aplicação
- [ ] Gate check passa: gate Full
- [ ] Test count: 130 + 3 novos = 133 testes passam

**Tests**: integration
**Gate**: full

**Commit**: `test(payment-api): prove the API fails closed during a real Redis outage`

---

### T7: Tornar `outbox-crash-window-reclaim` independente da decisão do Core

**What**: Trocar o filtro de tópico fixo por um conjunto dos dois tópicos terminais e reportar o estado terminal atingido quando nenhuma linha for encontrada.
**Where**: `scripts/e2e/payment-failures/scenarios/crash_recovery.sh`
**Depends on**: None
**Reuses**: `psql_sandbox` de `scripts/e2e/payment-failures/lib.sh`
**Requirement**: RES-07, RES-08

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Query usa `topic IN ('payment.simulation.completed','payment.simulation.failed')`
- [ ] A mensagem de falha do setup inclui o `status` da linha em `payment_sbus_message`, para que uma falha real seja distinguível de uma ausência
- [ ] Comentário registra por que o tópico não pode ser fixado
- [ ] Verificação: rodar o cenário forçando cada um dos dois desfechos e obter `PASS` nos dois
- [ ] Gate check passa: `scripts/verify-workspace.sh payment-failures`

**Tests**: none
**Gate**: build

**Commit**: `fix(gates): stop tying the outbox reclaim scenario to an approved simulation`

---

### T8: Execução ao vivo da matriz e evidência datada

**What**: Rodar a matriz de falhas ao vivo com a stack de pé, atingir `11/11`, e registrar o resultado datado na traceability do spec.
**Where**: `.specs/features/residual-resilience-findings/spec.md`
**Depends on**: T6, T7
**Reuses**: `scripts/verify-workspace.sh`, padrão de relatório datado de `load/reports/`
**Requirement**: RES-09

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `scripts/verify-workspace.sh payment-failures` reporta `11/11`
- [ ] A tabela de traceability do spec cita a data e a contagem da execução
- [ ] Todos os RES-01..RES-09 marcados `Verified`
- [ ] `python3 scripts/equivalence/equivalence.py verify --manifest scripts/equivalence/baseline-manifest.json` passa

**Tests**: none
**Gate**: build

**Commit**: `docs(state): record the live failure-matrix evidence for the residual findings`

---

## Phase Execution Map

```
Phase 1 ─────────────→ Phase 3
Phase 2 ─────────────→ Phase 3

Phase 1:  T1 ─┬─ T2 ──────────────┬─ T6
              └─ T3 ─┬─ T4 ───────┤
                     └─ T5 ───────┘
Phase 2:  T7
Phase 3:  T8   (fecha sobre T6 e T7)
```

Total: 8 tarefas → cabe num único batch (≤ ~8), execução inline, sem sub-agentes de batch. O Verifier ao final continua obrigatório.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1: `StoreUnavailableException` | 1 classe | ✅ Granular |
| T2: `StoreUnavailableExceptionHandler` | 1 classe | ✅ Granular |
| T3: Blindar `RedisStatusStore` | 1 arquivo | ✅ Granular |
| T4: Fallback durável no `getStatus` | 1 método, 1 arquivo | ✅ Granular |
| T5: Consumer não confirma offset | 1 arquivo | ✅ Granular |
| T6: IT de outage | 1 arquivo de teste | ✅ Granular |
| T7: Cenário de gate | 1 função, 1 arquivo | ✅ Granular |
| T8: Evidência datada | 1 arquivo | ✅ Granular |

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | (início da Phase 1) | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | T1 | T1 → T3 | ✅ Match |
| T4 | T3 | T3 → T4 | ✅ Match |
| T5 | T3 | T3 → T5 | ✅ Match |
| T6 | T2, T4, T5 | T2 → T6, T4 → T6, T5 → T6 | ✅ Match |
| T7 | None | (única tarefa da Phase 2) | ✅ Match |
| T8 | T6, T7 | T6 → T8, T7 → T8 | ✅ Match |

Nenhuma dependência aponta para fase posterior.

---

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1 | Error / exception (POJO) | none | none | ✅ OK |
| T2 | Error handler | unit | unit | ✅ OK |
| T3 | Redis store (data-access) | integration | integration | ✅ OK |
| T4 | Service (domain) | unit | unit | ✅ OK |
| T5 | Kafka consumer | unit | unit | ✅ OK |
| T6 | Controller / rota (e2e) | integration | integration | ✅ OK |
| T7 | Script de gate (bash) | none | none | ✅ OK |
| T8 | Documentação / traceability | none | none | ✅ OK |

Nenhum `Tests: none` é diferimento: T1 é POJO, T7/T8 não são camadas de código Java e são verificados pela execução ao vivo do gate.
