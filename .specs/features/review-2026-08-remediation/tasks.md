# Review 2026-08 Remediation Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is the source of truth for the full flow (per-task cycle, sub-agent delegation, adequacy review, Verifier, discrimination sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Design**: `.specs/features/review-2026-08-remediation/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Generated from codebase, project guidelines, and spec - confirm before Execute. Guidelines found: `docs/testing-policy.md`, root `AGENTS.md`, boundary `AGENTS.md` (testes são a especificação executável), convenção `*IT` com `-PwithIT` + Docker.

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Domínio/serviço Java (validação, idempotência, resolvers, cálculo) | unit | Todas as branches; 1:1 com ACs da spec; todo edge case listado | `<fronteira>/src/test/java/**/*Test.java` | `cd <fronteira> && ./gradlew test --no-daemon` |
| Fluxos com infra (Kafka/Redis/Postgres/HTTP entre fronteiras) | integration | Happy path + cada falha listada na story (`*IT`, exige Docker) | `<fronteira>/src/test/java/**/*IT.java` | `cd <fronteira> && ./gradlew test -PwithIT --no-daemon` |
| Migrations Flyway | integration | Aplicação limpa + upgrade sobre dados `legacy` | `payment-sbus/src/test/java/**/*IT.java` | `cd payment-sbus && ./gradlew test -PwithIT --no-daemon` |
| Scripts Python de gate/paridade | unit | Casos-chave: paridade OK, divergência detectada | `scripts/**/test_*.py`, `gateway/scripts/test_*.py` | `python3 -m unittest discover -s <dir> -p 'test_*.py'` |
| Config, compose, manifests K8s, docs, ADR | none | Gate estrutural (compose config, kubeconform, validate_docs) | - | build gate only |

## Gate Check Commands

> Generated from codebase - confirm before Execute.

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Tarefas só com unit tests | `cd <fronteira> && ./gradlew test --no-daemon` |
| Full | Tarefas com `*IT` (exige Docker) | `cd <fronteira> && ./gradlew test -PwithIT --no-daemon` |
| Build | Config/manifests/docs e fechamento de fase | `cd <fronteira> && ./gradlew build --no-daemon` (Java) ou `make config` (gateway/sandbox) + `python3 scripts/docs/validate_docs.py` + `python3 scripts/workspace/check_root_governance.py` |

> Ambiente sem Docker (ex.: este remoto): gates Full rodam onde houver Docker (CI ou máquina local); a tarefa só é `done` com o Full verde.

---

## Execution Plan

Phases are ordered and run sequentially - each phase completes before the next begins, and tasks within a phase execute in order.

### Phase 1: Contratos e política (payment-contracts)

```
T1 → T2 → T3
```

### Phase 2: Edge - tenant e idempotência

```
T3 → T4 → T5 → T6 → T7 → T8 → T9
```

### Phase 3: Sbus - tenant e contrato interno

```
T3 → T10 → T11 → T12 → T13 → T14 → T15
```

### Phase 4: Edge - orçamentos, filtros e segurança

```
T9 → T16 → T17 → T18 → T19 → T20 → T21
```

### Phase 5: Edge - correlação e admissão

```
T9 → T22 → T23 → T24 → T25 → T26
```

### Phase 6: Sbus - resiliência e segurança

```
T15 → T27 → T28 → T29 → T30 → T31 → T32 → T33
```

### Phase 7: Sbus - observabilidade e escala

```
T33 → T34 → T35 → T36 → T37 → T38 → T39 → T40 → T41
```

### Phase 8: Gateway - Kubernetes e compose

```
T41 → T42 → T43 → T44 → T45 → T46 → T47
```

### Phase 9: Fechamento

```
T47 → T48 → T49 → T50
```

---

## Task Breakdown

### T1: Envelope com tenantId e header x-tenant-id

**What**: Adicionar `tenantId` (default `""`) ao `EventEnvelope` POJO + `.avsc` (+history), header `x-tenant-id` em `Headers`/`HeaderMap`, helper `EnvelopeVersions.assertKnownMajor(...)`, fixtures atualizadas.
**Where**: `payment-contracts/`
**Depends on**: None
**Reuses**: fluxo de evolução/fixtures existente em `payment-contracts/schemas/`
**Requirement**: TEN-05, API-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Campo aditivo com default passa no gate de compatibilidade local
- [x] `assertKnownMajor` lança exceção tipada para major ≠ 1
- [x] Gate check passes: `cd payment-contracts && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-contracts): tenantId no envelope e validação de major`

---

### T2: Teste de guarda de campos sensíveis + ADR

**What**: Teste na library de contratos que falha se campo da denylist (`pan`, `card`, `cvv`, `cvc`, `password`, `secret`, `token`) aparecer nos modelos de payload; ADR da política de dados sensíveis.
**Where**: `payment-contracts/contract-model/`
**Depends on**: T1
**Reuses**: varredura reflexiva dos modelos já centralizados na library
**Requirement**: SEC-07

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Teste vermelho ao adicionar campo `cardNumber` num modelo (provado e revertido)
- [x] ADR em `payment-contracts/docs/adr/` registra denylist e evolução
- [x] Gate check passes: `cd payment-contracts && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-contracts): guarda de campos sensíveis com ADR`

---

### T3: Bump 0.2.0 e publicação local

**What**: Versão 0.2.0 do artefato contracts, history/compatibility atualizados, publicação no repositório local de build para as fronteiras consumidoras.
**Where**: `payment-contracts/gradle.properties`
**Depends on**: T2
**Reuses**: `publishAllToLocalBuildRepository`
**Requirement**: TEN-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] `./gradlew build` e gate de compatibilidade verdes
- [x] Artefato 0.2.0 resolvível por GAV pelas fronteiras

**Tests**: none
**Gate**: build
**Commit**: `chore(payment-contracts): release 0.2.0 com escopo de tenant`

---

### T4: TenantResolver com binding e guard de boot

**What**: `TenantResolver` (binding `payment.security.tenants` hash→tenants; resolve efetivo; 403 fora do binding; 400 ausente com multi-tenant) + guard que derruba boot com binding vazio/malformado.
**Where**: `payment-api/src/main/java/com/example/payments/api/tenant/`
**Depends on**: T3
**Reuses**: hash SHA-256 de `ConcurrencyLimitFilter`; padrão de `ProductionSecurityGuard`
**Requirement**: TEN-01, TEN-02, TEN-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Unit tests 1:1 com ACs 3-5 da story P1 (403/uso do único/400 multi)
- [x] Boot falha com binding vazio (teste de contexto)
- [x] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-api): tenant resolver com binding credencial->tenants`

---

### T5: Contrato de entrada: Idempotency-Key obrigatória e limites de DTO

**What**: `Idempotency-Key` obrigatória (400 ausente), `@Size(1..128)` + `@Pattern([A-Za-z0-9_-]+)`, `@Size`/`@Pattern` em `merchantId` e `captureMode`; respostas `problem+json`.
**Where**: `payment-api/src/main/java/com/example/payments/api/controller/`
**Depends on**: T4
**Reuses**: handlers `problem+json` existentes
**Requirement**: IDEM-01, IDEM-02, SEC-08

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] 400 sem header/formatos inválidos, sem I/O de domínio (verificado por mock)
- [x] Aplica-se a `/payment-simulations` e `/v0/...`
- [x] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-api): Idempotency-Key obrigatória e limites de entrada`

---

### T6: Reserva de idempotência escopada por tenant

**What**: Chaves `idem:{tenant}:{key}`, fingerprint incluindo tenant, TTL default 24h configurável, replay/409 escopados.
**Where**: `payment-api/src/main/java/com/example/payments/api/redis/RedisStatusStore.java`
**Depends on**: T5
**Reuses**: scripts Lua de reserva atômica existentes
**Requirement**: TEN-04 (parcial), TEN-05, IDEM-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT: mesma chave em tenants distintos gera reservas independentes
- [x] IT: payload diferente no mesmo tenant → 409 dentro da janela de 24h
- [x] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-api): idempotência escopada por tenant com janela de contrato`

---

### T7: Propagação de tenant até o Kafka

**What**: `ApiPaymentService` compõe tenant no envelope (contracts 0.2.0) e header `x-tenant-id`; MDC ganha `tenantId`.
**Where**: `payment-api/src/main/java/com/example/payments/api/service/ApiPaymentService.java`
**Depends on**: T6
**Reuses**: `PaymentRequestProducer` e `Headers` do contracts
**Requirement**: TEN-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT prova envelope+header com tenant do binding
- [x] Logs do fluxo carregam `tenantId` no MDC
- [x] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-api): propagação de tenant no envelope e headers`

---

### T8: IT cross-tenant de isolamento

**What**: IT dedicado com duas API keys/bindings: replay cross-tenant vira operação nova; 409 só no dono; forja de header → 403.
**Where**: `payment-api/src/test/java/com/example/payments/api/CrossTenantIsolationIT.java`
**Depends on**: T7
**Reuses**: harness de ITs existente (Kafka/Redis testcontainers)
**Requirement**: TEN-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Cenários do Independent Test da story P1 todos verdes
- [x] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `test(payment-api): isolamento cross-tenant de idempotência e status`

---

### T9: Atualizar chamadores do contrato (smoke, k6, e2e)

**What**: `scripts/smoke.sh`, `load/k6/capacity.js`, cenários `scripts/e2e/payment-failures/`, `scripts/demo-features.sh` e `gateway/scripts/smoke.sh` passam a enviar `Idempotency-Key` e `X-Tenant-Id` válidos; `.env.example` do payment-api documenta o binding.
**Where**: `scripts/`
**Depends on**: T8
**Reuses**: chaves de teste existentes dos scripts
**Requirement**: IDEM-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Nenhum chamador do workspace envia requisição sem os novos headers
- [x] `python3 scripts/docs/validate_docs.py` verde (variáveis novas documentadas)

**Tests**: none
**Gate**: build
**Commit**: `chore(workspace): chamadores atualizados para o contrato com tenant`

---

### T10: Migration V12 de escopo de tenant

**What**: `tenant_id` NOT NULL DEFAULT `'legacy'` em `payment_sbus_message` e `idempotency_record`; unique composto `(tenant_id, idempotency_key)` substitui a global; entidades/repos atualizados.
**Where**: `payment-sbus/src/main/resources/db/migration/V12__tenant_scope.sql`
**Depends on**: T3
**Reuses**: padrão das migrations V1-V11
**Requirement**: TEN-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT de migration: base V11 com dados migra limpa para V12
- [x] Unicidade composta provada (mesma chave, tenants distintos, ambas inserem)
- [x] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): escopo de tenant no schema com unique composto`

---

### T11: Persistência e replay escopados por tenant

**What**: `PaymentPersistenceService`/`SimulationMessageHandler` leem `tenantId` do envelope (fallback `legacy` para `""`), gravam e resolvem replay por `(tenant, key)`.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/service/`
**Depends on**: T10
**Reuses**: fluxo transacional e corrida AUD-11 existentes
**Requirement**: TEN-05, TEN-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT: eventos de tenants distintos com mesma chave processam independentes
- [x] Envelope com tenant vazio cai em `legacy` sem erro
- [x] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): replay e persistência por tenant`

---

### T12: Constraint violation classificada como poison

**What**: Violações de integridade (SQLState 22xxx/23xxx) na persistência classificam a mensagem como poison → DLQ direta com razão sanitizada, nunca retry transiente.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java`
**Depends on**: T11
**Reuses**: discriminação poison/transiente existente
**Requirement**: IDEM-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT: chave >128 chars injetada via Kafka vai à DLQ sem passar pelos retries
- [x] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): violação de constraint é poison, não transiente`

---

### T13: Validação de eventVersion nos consumers do Sbus

**What**: Consumers do Sbus chamam `EnvelopeVersions.assertKnownMajor`; major desconhecido → DLQ poison com razão explícita.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/kafka/`
**Depends on**: T12
**Reuses**: helper do T1; caminho poison do T12
**Requirement**: API-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] IT: evento `eventVersion: 99.0` → DLQ com razão `unknown-major`
- [x] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): rejeição de major desconhecido de evento`

---

### T14: Fixture do contrato interno no Sbus

**What**: Fixture JSON canônica de `SbusStatusView` + teste que serializa o view real contra a fixture.
**Where**: `payment-sbus/src/test/resources/contracts/internal-status.json`
**Depends on**: T13
**Reuses**: Jackson já configurado
**Requirement**: API-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Teste falha ao renomear campo do view (provado e revertido)
- [x] Gate check passes: `cd payment-sbus && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `test(payment-sbus): fixture do contrato interno de status`

---

### T15: Contrato interno no Edge + verificador cross-boundary

**What**: Fixture idêntica no payment-api testada contra `SbusStatusResponse`; script `scripts/e2e/check_internal_contract.py` compara as duas fixtures byte a byte e entra no `verify-workspace.sh`.
**Where**: `scripts/e2e/check_internal_contract.py`
**Depends on**: T14
**Reuses**: padrão dos verificadores python do workspace
**Requirement**: API-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [x] Divergência de fixture derruba o gate (provado e revertido)
- [x] `scripts/verify-workspace.sh hygiene` continua verde

**Tests**: unit
**Gate**: quick
**Commit**: `test(workspace): contrato interno Edge-Sbus verificado por fixture`

---

### T16: Orçamento de publicação do Edge

**What**: `payment.publish-budget` tipado derivando `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`; guard `publish-budget < wait-timeout`; ajuste do `PublishFailureIT` para provar 503 dentro do orçamento.
**Where**: `payment-api/src/main/java/com/example/payments/api/config/`
**Depends on**: T9
**Reuses**: padrão `KafkaProducerFactory` do Sbus
**Requirement**: BUDG-01, BUDG-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Boot falha com budget ≥ wait-timeout (teste de contexto)
- [ ] IT com broker parado: 503 em < wait-timeout + 1s
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-api): orçamento de publicação fechado no caminho da requisição`

---

### T17: Filtros fora do event loop

**What**: Migrar `ApiKeyFilter` e `ConcurrencyLimitFilter` para `@ServerFilter`/`@RequestFilter` executando em `TaskExecutors.BLOCKING`, ordem e semântica preservadas.
**Where**: `payment-api/src/main/java/com/example/payments/api/filter/`
**Depends on**: T16
**Reuses**: ITs de admissão existentes como rede de segurança
**Requirement**: BUDG-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Asserção de thread nos testes: filtro nunca roda em thread de event loop
- [ ] ITs de admissão existentes verdes sem alteração de expectativa
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-api): admissão fora do event loop do Netty`

---

### T18: IT de liveness sob Redis latente

**What**: IT que injeta latência ≥ 2s no Redis (proxy de atraso) e prova `/health/liveness` < 500ms durante o degradado.
**Where**: `payment-api/src/test/java/com/example/payments/api/LivenessUnderRedisLatencyIT.java`
**Depends on**: T17
**Reuses**: Toxiproxy testcontainers (novo dep de teste) ou socket proxy local
**Requirement**: BUDG-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Liveness medido < 500ms com admissão degradando (429/fração) em paralelo
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `test(payment-api): liveness responsivo sob Redis latente`

---

### T19: API keys em hash com comparação constant-time

**What**: `ApiKeyFilter` compara via `MessageDigest.isEqual` sobre SHA-256; config aceita `sha256:<hex>`; guard de prod proíbe key em claro; dev segue aceitando claro.
**Where**: `payment-api/src/main/java/com/example/payments/api/filter/ApiKeyFilter.java`
**Depends on**: T18
**Reuses**: `SecurityProperties` + `ProductionSecurityGuard`
**Requirement**: SEC-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Unit: aceitação por hash, rejeição, boot prod com claro falha
- [ ] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `fix(payment-api): comparação constant-time e keys em hash`

---

### T20: Governança de schema e logging do Edge

**What**: `payments.avro.auto-register: false` no `application-prod.yml` + verificação no `ProductionSecurityGuard`; logback default INFO com nível por env (`LOG_LEVEL_APP`).
**Where**: `payment-api/src/main/resources/`
**Depends on**: T19
**Reuses**: guard existente
**Requirement**: SEC-01, SEC-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Boot prod com auto-register true falha (teste de contexto)
- [ ] Nível de log muda por env sem rebuild (teste de config)
- [ ] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `fix(payment-api): auto-register off em prod e log INFO por default`

---

### T21: Credencial de serviço no fallback Edge→Sbus

**What**: Client filter no `SbusStatusClient` anexando JWT com `ROLE_PAYMENT_API` (HS256 dev / config externa prod); IT com segurança do Sbus ligada prova 200.
**Where**: `payment-api/src/main/java/com/example/payments/api/client/`
**Depends on**: T20
**Reuses**: config JWT existente das duas fronteiras
**Requirement**: SEC-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: fallback autenticado retorna 200 com role exigida ativa
- [ ] Falha de auth continua degradando para `Optional.empty()` + métrica nova de auth-failure
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-api): fallback interno autenticado com role de serviço`

---

### T22: Re-poll do waiter dentro do orçamento

**What**: `ResponseCoordinator.await` alterna `future.get(500ms)` com releitura do store até o orçamento; wake-up perdido deixa de virar 202.
**Where**: `payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java`
**Depends on**: T9
**Reuses**: `completeFromStore` existente
**Requirement**: OBS-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: PUBLISH suprimido → 200 dentro do wait-timeout via re-poll
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-api): correlação at-least-once com re-poll do waiter`

---

### T23: Correlation-id herdado da borda

**What**: Aceitar `x-correlation-id` de entrada (UUID ou `[A-Za-z0-9-]{8,64}`); inválido → ignora e gera; propaga a MDC/eventos e devolve no response header.
**Where**: `payment-api/src/main/java/com/example/payments/api/service/ApiPaymentService.java`
**Depends on**: T22
**Reuses**: MDC e envelope existentes
**Requirement**: OBS-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Unit: válido adotado, inválido ignorado (nunca 4xx), response ecoa o id
- [ ] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-api): herança validada de correlation-id`

---

### T24: Sharding do canal de correlação

**What**: Canais `payment-sim-responses-{hash % N}` (config `response-channel-shards`, default 4), assinatura por shard com fallback assinar-todos; canal legado mantido por uma release.
**Where**: `payment-api/src/main/java/com/example/payments/api/coordination/`
**Depends on**: T23
**Reuses**: conexão pub/sub e dispatcher em virtual thread existentes
**Requirement**: SCAL-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT com N=2: waiters em shards distintos acordam corretamente
- [ ] Publicação também no canal legado durante a transição (flag)
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-api): canal de correlação shardado`

---

### T25: Limiter do Edge com EVALSHA e janela deslizante

**What**: `EVALSHA` com fallback NOSCRIPT; janela deslizante ponderada no script Lua (burst 2× na fronteira rejeitado); divisor degradado inalterado.
**Where**: `payment-api/src/main/java/com/example/payments/api/ratelimit/RedisRateLimiter.java`
**Depends on**: T24
**Reuses**: script Lua atual como base
**Requirement**: SCAL-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: 20 req no fim + 20 no início de janela → excedente rejeitado
- [ ] NOSCRIPT recarrega sem negar a requisição corrente
- [ ] Gate check passes: `cd payment-api && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-api): janela deslizante e EVALSHA na admissão`

---

### T26: Validação de eventVersion no consumer do Edge

**What**: `PaymentResponseConsumer` valida major via helper do contracts; desconhecido vai à DLQ de resposta existente com razão explícita.
**Where**: `payment-api/src/main/java/com/example/payments/api/kafka/PaymentResponseConsumer.java`
**Depends on**: T25
**Reuses**: caminho de DLQ failure-safe existente do consumer
**Requirement**: API-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Unit: major desconhecido → DLQ, conhecido → fluxo normal
- [ ] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-api): rejeição de major desconhecido no consumer de respostas`

---

### T27: Jitter no backoff durável

**What**: Jitter decorrelated (±20-50%) em `BackoffCalculator`, preservando teto; testes provam dispersão de vencimentos para lote com mesmo `attempts`.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/BackoffCalculator.java`
**Depends on**: T15
**Reuses**: `BackoffCalculatorUnitTest` existente
**Requirement**: RES-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Distribuição de N=100 atrasos com mesmo attempts tem dispersão ≥ 20%
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `fix(payment-sbus): jitter decorrelated no backoff`

---

### T28: Housekeeping proporcional à ingestão

**What**: `OutboxHousekeeping`/`RetentionHousekeeping` iteram lotes até esgotar ou teto de 30s; intervalo 5min; métricas purgado/restante.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/`
**Depends on**: T27
**Reuses**: deleção por lote existente (AUD-24)
**Requirement**: RES-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: backlog de 10 lotes purgado numa execução; teto de tempo respeitado
- [ ] Métricas expostas e documentadas em observability.md
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): housekeeping drena backlog com teto de tempo`

---

### T29: Readiness em três níveis

**What**: `DependencyPolicies` aceita `readiness-required:false`; Redis do Sbus vira DEGRADED (reportado em `/health`, fora da readiness); Kafka/Postgres seguem readiness.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/config/DependencyPolicies.java`
**Depends on**: T28
**Reuses**: health indicators com budget existentes
**Requirement**: RES-03, RES-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: Redis parado → readiness UP + componente DEGRADED + `/internal/...` respondendo
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): readiness desacoplada de dependência não crítica`

---

### T30: Saúde e métricas do pool Hikari

**What**: Health indicator `postgresql-pool` (aquisição com timeout curto), gauges Hikari expostos, alerta em `ops/alerts/` com runbook.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/health/`
**Depends on**: T29
**Reuses**: molde dos indicators existentes; pipeline de alertas montado pelo sandbox
**Requirement**: RES-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: pool esgotado → indicator DOWN dentro do budget
- [ ] Alerta validado pelo validador de dashboards/alertas do workspace
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): observabilidade e saúde do pool de conexões`

---

### T31: Drain do outbox no shutdown

**What**: `@PreDestroy` no dispatcher: para de reivindicar, libera claims não publicados sem `attempts++`; reaper distingue release limpo de crash.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxDispatcher.java`
**Depends on**: T30
**Reuses**: `releaseClaim` fenceado existente
**Requirement**: RES-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: shutdown com lote em voo → linhas PENDING com attempts inalterado
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): shutdown ordenado libera claims sem penalizar`

---

### T32: Logs sem payload e sanitização

**What**: `RetryPublisher` loga só ponteiro (`topic/partition/offset/key`); sanitizador para `x-retry-reason`/`last_error` (trunca, remove conteúdo de payload); logback INFO por env.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/kafka/RetryPublisher.java`
**Depends on**: T31
**Reuses**: truncamento de `last_error` existente
**Requirement**: SEC-02, SEC-03, SEC-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Unit: log de risco não contém base64/payload; headers sanitizados
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `fix(payment-sbus): logs apontam, não copiam, o payload`

---

### T33: Limiter do Sbus com EVALSHA e janela deslizante

**What**: Mesmo tratamento do T25 no rate limiter global do Core.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/ratelimit/RedisRateLimiter.java`
**Depends on**: T32
**Reuses**: script do T25 adaptado
**Requirement**: SCAL-06

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Testes equivalentes ao T25 no limiter do Sbus verdes
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): janela deslizante e EVALSHA no limiter do Core`

---

### T34: Trace através do outbox e eventos finais

**What**: Persistir `traceparent` na ingestão; publish do outbox cria span próprio com `Link` para o contexto persistido; eventos finais carregam `traceparent` corrente.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxDispatcher.java`
**Depends on**: T33
**Reuses**: OTel API já no classpath; `HeaderMap.from(event, traceparent)`
**Requirement**: OBS-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: evento final chega ao consumer com traceparent válido; span de publish com link (span exporter de teste)
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): contexto W3C atravessa o outbox com span de publish`

---

### T35: Gauges de contagem cacheados + pool de codecs (Sbus)

**What**: `SbusMetrics` lê contagens de cache com TTL 15s (refresh assíncrono); registra gauges do `AvroSerde.poolSnapshot()`.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/metrics/SbusMetrics.java`
**Depends on**: T34
**Reuses**: `poolSnapshot()` existente
**Requirement**: OBS-04, OBS-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Unit: scrapes consecutivos dentro do TTL disparam uma única query
- [ ] Gauges de codec visíveis no registry de teste
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `fix(payment-sbus): métricas sem COUNT(*) por scrape e pool de codecs exposto`

---

### T36: Pool de codecs exposto no Edge

**What**: `ApiMetrics` registra gauges do pool de codecs Avro do Edge.
**Where**: `payment-api/src/main/java/com/example/payments/api/metrics/ApiMetrics.java`
**Depends on**: T35
**Reuses**: `poolSnapshot()` + guarda de cardinalidade existente
**Requirement**: OBS-05

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Gauges visíveis no registry de teste do Edge
- [ ] Gate check passes: `cd payment-api && ./gradlew test --no-daemon`

**Tests**: unit
**Gate**: quick
**Commit**: `feat(payment-api): métricas do pool de codecs`

---

### T37: max.poll.interval curto com espera no retry durável

**What**: `max.poll.interval.ms` ≤ 5min; retries longos de consumo movem a mensagem para o tópico de retry durável em vez de segurar a partição.
**Where**: `payment-sbus/src/main/resources/application.yml`
**Depends on**: T36
**Reuses**: `DurableRetryScheduler` e retry topics existentes
**Requirement**: SCAL-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: Postgres indisponível prolongado → mensagens fluem ao retry durável, partição não fica presa > 5min (sem rebalance forçado no teste)
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): partição nunca presa além de 5 minutos`

---

### T38: Publicação paralela do lote do outbox

**What**: Sends do lote disparados em paralelo com coleta de futures; `markPublished` por item e renovação de lease preservados.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxDispatcher.java`
**Depends on**: T37
**Reuses**: `KafkaPublisher` e fencing existentes
**Requirement**: SCAL-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: lote de 10 com um send lento não serializa os demais; zero duplicação sob crash simulado
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): outbox publica lote em paralelo`

---

### T39: Concorrência configurável dos listeners

**What**: `threads` configurável por listener nos consumers do Sbus (default 3) e do core-mock (remoção do sleep inline serializante documentada).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/kafka/`
**Depends on**: T38
**Reuses**: propriedades tipadas de consumers existentes
**Requirement**: SCAL-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: com threads=3, três mensagens de chaves distintas processam concorrentes
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `feat(payment-sbus): concorrência de consumo configurável`

---

### T40: Partições parametrizadas no sandbox

**What**: `KAFKA_TOPIC_PARTITIONS` (default 6) na criação de tópicos do sandbox; docs de dimensionamento.
**Where**: `sandbox/smoke/init.sh`
**Depends on**: T39
**Reuses**: init de tópicos existente
**Requirement**: SCAL-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Variável documentada em `.env.example` e docs do sandbox
- [ ] `make config` e validadores do sandbox verdes

**Tests**: none
**Gate**: build
**Commit**: `feat(sandbox): partições de tópicos parametrizadas`

---

### T41: OutboxPublicationLock explícito e namespaced

**What**: try-with-resources na conexão; `pg_try_advisory_lock(classid, objid)` com classid dedicado; teste de não-vazamento de conexões após N publicações.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxPublicationLock.java`
**Depends on**: T40
**Reuses**: gauges Hikari do T30 para a asserção
**Requirement**: SCAL-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] IT: 50 publicações → conexões ativas voltam ao baseline
- [ ] Gate check passes: `cd payment-sbus && ./gradlew test -PwithIT --no-daemon`

**Tests**: integration
**Gate**: full
**Commit**: `fix(payment-sbus): lock de publicação sem vazamento e com namespace`

---

### T42: Manifests base Gateway API

**What**: `gateway/k8s/base/`: Gateway, HTTPRoutes (allowlist 1:1 com envoy.yaml), SecurityPolicy (JWT Keycloak + claim→`X-Tenant-Id`), BackendTrafficPolicy (rate limit, circuit breaking, retry/timeout por rota), EnvoyProxy (access log JSON, OTLP), kustomization.
**Where**: `gateway/k8s/base/`
**Depends on**: T41
**Reuses**: semântica documentada do `gateway/envoy/envoy.yaml`
**Requirement**: K8S-01, K8S-02

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `kubectl kustomize` (ou `kustomize build`) renderiza sem erro
- [ ] Semântica espelhada: mesma allowlist, retry POST restrito, timeout > wait-timeout

**Tests**: none
**Gate**: build
**Commit**: `feat(gateway): manifests Gateway API equivalentes ao compose`

---

### T43: Overlays sandbox e prod-example

**What**: `overlays/sandbox` (namespaces/hosts locais, kind) e `overlays/prod-example` (TLS/certificados placeholders, réplicas, anotações).
**Where**: `gateway/k8s/overlays/`
**Depends on**: T42
**Reuses**: base do T42
**Requirement**: K8S-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Ambos os overlays renderizam via kustomize
- [ ] Diferenças entre overlays documentadas no README do k8s

**Tests**: none
**Gate**: build
**Commit**: `feat(gateway): overlays kustomize sandbox e prod-example`

---

### T44: kubeconform no CI com schemas vendorizados

**What**: Schemas JSON das CRDs usadas (Gateway API + Envoy Gateway) vendorizados em `gateway/k8s/schemas/`; `make config` do gateway passa a validar os manifests com kubeconform; job de CI atualizado.
**Where**: `gateway/k8s/schemas/`
**Depends on**: T43
**Reuses**: job `gateway` do CI raiz
**Requirement**: K8S-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] kubeconform verde localmente e no CI; manifest inválido reprovado (provado e revertido)
- [ ] Fonte dos schemas documentada (gerados das CRDs oficiais se catálogo não cobrir)

**Tests**: none
**Gate**: build
**Commit**: `feat(gateway): validação kubeconform dos manifests no CI`

---

### T45: Script de paridade compose↔K8s

**What**: `gateway/scripts/check-k8s-parity.py`: allowlist (método+prefixo), timeouts e limites de rate comparados entre `envoy.yaml`/`ratelimit/config.yaml` e os CRDs; unittest próprio; wire no `make config`.
**Where**: `gateway/scripts/check-k8s-parity.py`
**Depends on**: T44
**Reuses**: padrão de `gateway/scripts/validate-config.py`
**Requirement**: K8S-03

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Remoção de rota de um lado derruba o gate (provado no unittest)
- [ ] `python3 -m unittest` do script verde; `make config` inclui a paridade

**Tests**: unit
**Gate**: quick
**Commit**: `feat(gateway): gate de paridade semântica compose-k8s`

---

### T46: Compose injeta X-Tenant-Id do claim

**What**: `jwt_authn.claim_to_headers` injeta `X-Tenant-Id` (claim configurado no realm); header de entrada removido antes; realm Keycloak ganha claim de tenant nos usuários de teste; smoke do gateway atualizado para validar a injeção.
**Where**: `gateway/envoy/envoy.yaml`
**Depends on**: T45
**Reuses**: realm import e smoke existentes
**Requirement**: TEN-07, K8S-04

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `make config` verde; smoke prova header injetado chegando ao Edge (em ambiente com Docker)
- [ ] Header forjado pelo cliente é sobrescrito pelo gateway

**Tests**: none
**Gate**: build
**Commit**: `feat(gateway): injeção de tenant a partir do claim JWT`

---

### T47: Documentação do gateway e da borda

**What**: Docs do gateway (compose vs K8s, kind como exemplo, pré-requisito de CRDs), `payment-api/docs/contracts.md` (chave obrigatória, janela, tenant), `docs/payment-flow.md` e `production-evidence.md` atualizados.
**Where**: `gateway/docs/`
**Depends on**: T46
**Reuses**: estrutura de docs existente
**Requirement**: K8S-05, API-01 (parcial)

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `python3 scripts/docs/validate_docs.py` verde
- [ ] Links, portas e variáveis novos validados pelo gate

**Tests**: none
**Gate**: build
**Commit**: `docs(gateway): caminhos compose e kubernetes documentados`

---

### T48: ADRs e estratégia de versionamento

**What**: Registrar AD-008/009/010 em `.specs/STATE.md`; ADR de versionamento de API (path major, `X-Api-Version`, política de `eventVersion`) em `payment-api/docs/adr/`; revisar rotas/docs para consistência com a regra.
**Where**: `.specs/STATE.md`
**Depends on**: T47
**Reuses**: formato AD-NNN existente
**Requirement**: API-01

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] ADs registrados; ADR publicado; docs coerentes com a estratégia
- [ ] `python3 scripts/docs/validate_docs.py` verde

**Tests**: none
**Gate**: build
**Commit**: `docs(workspace): AD-008/009/010 e estratégia de versionamento`

---

### T49: Gates de workspace e reconciliação

**What**: Rodar todos os gates estruturais (equivalence com manifest reconciliado, docs, governança, CI policy, sandbox estrutural); atualizar `docs/architecture-review-2026-08.md` marcando cada achado como resolvido com ponteiro para o commit/tarefa.
**Where**: `docs/architecture-review-2026-08.md`
**Depends on**: T48
**Reuses**: procedimento de reconciliação do baseline já documentado
**Requirement**: (fechamento - todos)

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] `verify-workspace.sh` estágios estruturais + validate_docs + governança verdes
- [ ] Tabela da revisão com status por achado

**Tests**: none
**Gate**: build
**Commit**: `docs(workspace): revisão 2026-08 fechada com gates verdes`

---

### T50: Re-execução do gate de capacidade AD-007

**What**: Rodar o gate de capacidade completo (steady 15min + spike) em ambiente com Docker, gerar relatório datado em `load/reports/` e confirmar os alvos AD-007 após todas as mudanças.
**Where**: `load/capacity/`
**Depends on**: T49
**Reuses**: `run_gate.sh` + k6 atualizado no T9
**Requirement**: (Success Criteria)

**Tools**:
- MCP: NONE
- Skill: NONE

**Done when**:
- [ ] Veredito PASS: 1.000 req/min sustentado, avg ≤ 300ms, p99 ≤ 10s, 429 ≤ 1% no steady
- [ ] Relatório datado commitado

**Tests**: none
**Gate**: build
**Commit**: `test(load): capacidade AD-007 revalidada pós-remediação`

> Dependente de ambiente: exige Docker + stack completa; roda em CI self-hosted ou máquina local, nunca neste ambiente remoto sem Docker.

---

## Phase Execution Map

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8 → Phase 9

Phase 1:  T1 → T2 → T3
Phase 2:  T3 → T4 → T5 → T6 → T7 → T8 → T9
Phase 3:  T3 → T10 → T11 → T12 → T13 → T14 → T15
Phase 4:  T9 → T16 → T17 → T18 → T19 → T20 → T21
Phase 5:  T9 → T22 → T23 → T24 → T25 → T26
Phase 6:  T15 → T27 → T28 → T29 → T30 → T31 → T32 → T33
Phase 7:  T33 → T34 → T35 → T36 → T37 → T38 → T39 → T40 → T41
Phase 8:  T41 → T42 → T43 → T44 → T45 → T46 → T47
Phase 9:  T47 → T48 → T49 → T50
```

Execution is strictly sequential - there is no intra-phase parallelism.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1-T3 | 1 fronteira, 1 tema por task (envelope / guarda / release) | ✅ Granular |
| T4-T8 | 1 componente ou arquivo por task | ✅ Granular |
| T9 | vários scripts, 1 tema (mesma mudança de contrato replicada) | ⚠️ Coeso - aceito |
| T10-T15 | 1 migration / 1 serviço / 1 classificação / fixtures por task | ✅ Granular |
| T16-T26 | 1 config / 1 par de filtros / 1 IT / 1 arquivo por task | ✅ Granular |
| T27-T41 | 1 mecanismo por task | ✅ Granular |
| T42-T47 | 1 diretório de manifests / 1 script / 1 config por task | ✅ Granular |
| T48-T50 | 1 artefato de fechamento por task | ✅ Granular |

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | início da Phase 1 | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | T2 | T2 → T3 | ✅ Match |
| T4 | T3 (cross-phase) | início da Phase 2 | ✅ Match |
| T5-T9 | anterior imediato | cadeia da Phase 2 | ✅ Match |
| T10 | T3 (cross-phase) | início da Phase 3 | ✅ Match |
| T11-T15 | anterior imediato | cadeia da Phase 3 | ✅ Match |
| T16 | T9 (cross-phase) | início da Phase 4 | ✅ Match |
| T17-T21 | anterior imediato | cadeia da Phase 4 | ✅ Match |
| T22 | T9 (cross-phase) | início da Phase 5 | ✅ Match |
| T23-T26 | anterior imediato | cadeia da Phase 5 | ✅ Match |
| T27 | T15 (cross-phase) | início da Phase 6 | ✅ Match |
| T28-T33 | anterior imediato | cadeia da Phase 6 | ✅ Match |
| T34 | T33 (cross-phase) | início da Phase 7 | ✅ Match |
| T35-T41 | anterior imediato | cadeia da Phase 7 | ✅ Match |
| T42 | T41 (cross-phase) | início da Phase 8 | ✅ Match |
| T43-T47 | anterior imediato | cadeia da Phase 8 | ✅ Match |
| T48 | T47 (cross-phase) | início da Phase 9 | ✅ Match |
| T49-T50 | anterior imediato | cadeia da Phase 9 | ✅ Match |

Nenhuma dependência aponta para fase posterior.

## Test Co-location Validation

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1, T2 | domínio contracts | unit | unit | ✅ OK |
| T3 | release/config | none | none | ✅ OK |
| T4, T5 | domínio Edge | unit | unit | ✅ OK |
| T6, T7, T8 | fluxo com infra (Redis/Kafka) | integration | integration | ✅ OK |
| T9 | scripts operacionais (harness de teste) | none | none | ✅ OK |
| T10-T13 | migration/fluxo com infra | integration | integration | ✅ OK |
| T14 | serialização (domínio) | unit | unit | ✅ OK |
| T15 | script python + teste api | unit | unit | ✅ OK |
| T16-T18 | fluxo com infra | integration | integration | ✅ OK |
| T19, T20 | domínio/config Edge | unit | unit | ✅ OK |
| T21, T22 | fluxo com infra | integration | integration | ✅ OK |
| T23 | domínio Edge | unit | unit | ✅ OK |
| T24, T25 | fluxo com infra | integration | integration | ✅ OK |
| T26 | domínio Edge | unit | unit | ✅ OK |
| T27 | domínio Sbus | unit | unit | ✅ OK |
| T28-T31 | fluxo com infra | integration | integration | ✅ OK |
| T32 | domínio Sbus | unit | unit | ✅ OK |
| T33, T34 | fluxo com infra | integration | integration | ✅ OK |
| T35, T36 | métricas (domínio) | unit | unit | ✅ OK |
| T37-T39 | fluxo com infra | integration | integration | ✅ OK |
| T40 | config sandbox | none | none | ✅ OK |
| T41 | fluxo com infra | integration | integration | ✅ OK |
| T42-T44 | manifests/config | none | none | ✅ OK |
| T45 | script python | unit | unit | ✅ OK |
| T46, T47 | config/docs | none | none | ✅ OK |
| T48-T50 | docs/gates/carga | none | none | ✅ OK |
