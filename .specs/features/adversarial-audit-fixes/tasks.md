# Adversarial Audit Fixes Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute flow and Critical Rules.** Do not search for skill files by filesystem path. The skill is the source of truth for the full flow (per-task cycle, sub-agent delegation, adequacy review, Verifier, discrimination sensor).

**If the skill cannot be activated, STOP and tell the user - do not proceed without it.**

---

**Spec**: `.specs/features/adversarial-audit-fixes/spec.md`
**Design**: `.specs/features/adversarial-audit-fixes/design.md`
**Status**: Draft

---

## Test Coverage Matrix

> Gerado do codebase e guidelines (`AGENTS.md` por fronteira; "o baseline só cresce — teste não pode ser apagado, ignorado ou enfraquecido"). Baselines medidos ao vivo: payment-api 134 (`-PwithIT`), payment-sbus 89, async-redis-service 101, feature-control 150 (113 quick).

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Resolver/serviço (domínio) | unit | 1:1 com ACs; todo edge case listado | `src/test/java/**/*UnitTest.java` | `./gradlew test --no-daemon` |
| Store Redis / repositório | integration | caminho principal + caminho de erro com dependência real derrubada | `src/test/java/**/*IT.java` | `./gradlew test -PwithIT --no-daemon` |
| Consumer Kafka / worker | integration | falha induzida real (broker/registry/postgres parado) | `src/test/java/**/*IT.java` | `./gradlew test -PwithIT --no-daemon` |
| Controller/rota | integration | happy + edge + erro por rota tocada | `src/test/java/**/*IT.java` | `./gradlew test -PwithIT --no-daemon` |
| Migration SQL / config YAML | none | gate de build + validação de startup existente | — | build |
| Script de gate (bash/py/k6) | none | verificado pela execução ao vivo do gate | `load/**`, `scripts/**` | `scripts/verify-workspace.sh` |

## Gate Check Commands

> `PAYMENT_API_KEY` e `JWT_SIGNATURE_SECRET` exportados para os ITs da API; Redis do sandbox em `localhost:6379` para async-redis e feature-control; porta 8085 (registry) livre para os ITs do pilot-app.

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | tarefas só com unit | `cd <fronteira> && ./gradlew test --no-daemon` |
| Full | tarefas com IT | `cd <fronteira> && ./gradlew test -PwithIT --no-daemon` (API: prefixar `PAYMENT_API_KEY=... JWT_SIGNATURE_SECRET=...`) |
| Build | fim de fase / config-only | `./gradlew build -x test --no-daemon` + `python3 scripts/equivalence/equivalence.py verify --root . --manifest scripts/equivalence/baseline-manifest.json` + `git diff --check` |
| Gate ao vivo | Fase 5 | `scripts/verify-workspace.sh` + cenário de capacidade completo (stack de pé) |

---

## Execution Plan

Fases por fronteira, sequenciais. Setas = dependência real (mesmo arquivo/fundação); tarefas sem seta são independentes e rodam em ordem.

### Phase 1: feature-control (críticos do kill-switch e cache)

```
T1 → T2
T2 → T4
T3
```

### Phase 2: payment-api (admissão, waiter, superfícies)

```
T5 → T6
T7
T8
T9
```

### Phase 3: payment-sbus (dinheiro, outbox, readiness, grupos)

```
T10 → T11
T12 → T13
T13 → T16
T14
T15
```

### Phase 4: async-redis-service (CAS, FAILED, reclaim)

```
T17 → T18
T19
T20
```

### Phase 5: recalibração e evidência

```
T21 → T22
T22 → T23
T23 → T24
```

---

## Task Breakdown

### T1: Resultado trinário no flag source + latch do MasterSwitch ✅

**What**: Interface interna `FOUND/ABSENT/UNAVAILABLE` no `RedisFlagSource` e latch `AtomicBoolean` no `MasterSwitch`: leitura bem-sucedida atualiza o latch; falha/stale responde o latch. Cold start desarmado (design §1).
**Where**: `feature-control/library/src/main/java/com/example/platform/featurecontrol/resolver/MasterSwitch.java`
**Depends on**: None
**Reuses**: `StalePolicy`, `RedisFlagSourceIT` (harness de Redis real)
**Requirement**: AUD-02

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] IT com Redis real parado: kill armado antes da queda permanece armado durante e após `max-stale`, nos dois `stale-fallback`
- [x] IT: kill removido com Redis saudável desarma (ABSENT ≠ UNAVAILABLE)
- [x] API pública certificada intacta (`scripts/verify_api_surface.py` passa)
- [x] Gate Full passa; baseline só cresce

**Tests**: integration
**Gate**: full

**Commit**: `fix(feature-control): latch the kill switch across Redis outages`

---

### T2: Janela de backoff de falha no RedisFlagSource ✅

**What**: Timestamp de falha por chave; dentro da janela (1s, jitterada) `find()` serve a política de stale sem lock e sem Redis (design §2).
**Where**: `feature-control/library/src/main/java/com/example/platform/featurecontrol/store/RedisFlagSource.java`
**Depends on**: T1
**Reuses**: cache entry existente, jitter de `cache-ttl-jitter`
**Requirement**: AUD-14

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] IT com Redis parado: N threads concorrentes lendo a mesma flag geram ≤ ~1 tentativa de Redis por segundo (asserção sobre contador de tentativas, não timing frouxo)
- [x] Recuperação ≤ ~1s após o Redis voltar (leitura fresca volta a servir)
- [x] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(feature-control): stop serializing evaluations on the flag lock during outages`

---

### T3: VARIANT isOn honesto + peso zero resolve off ✅

**What**: `variant()` compara a escolhida com `off-variant` (`isOn=false` quando iguais); `Bucketer.select` com soma de pesos ≤ 0 retorna null (off), não a primeira variante.
**Where**: `feature-control/library/src/main/java/com/example/platform/featurecontrol/resolver/FeatureResolver.java`
**Depends on**: None
**Reuses**: `FeatureResolverUnitTest`, `BucketerUnitTest`
**Requirement**: AUD-04, AUD-22

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] Unit: usuário bucketado na variante de controle recebe `isOn()==false` (o caso que abria `@FeatureGate`/`TopicRouter` para 100%)
- [x] Unit: pesos todos zero → off, com reason explícito
- [x] Gate Quick passa

**Tests**: unit
**Gate**: quick

**Commit**: `fix(feature-control): variant control arm no longer reports on`

---

### T4: Auditoria noop + guarda de geração no cache ✅

**What**: `DELETE_LUA` de flag inexistente audita `result: "noop"`; `refresh()` só assenta no cache se nenhuma invalidação chegou durante a leitura (contador de geração por chave).
**Where**: `feature-control/library/src/main/java/com/example/platform/featurecontrol/store/VersionedFlagStore.java`
**Depends on**: T2
**Reuses**: `VersionedFlagStoreIT`
**Requirement**: AUD-21, AUD-26

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] IT: delete de inexistente → stream de auditoria registra `noop`
- [x] IT: invalidação durante refresh em andamento não é perdida (o valor pós-escrita vence)
- [x] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(feature-control): honest delete audit and invalidation-safe cache refresh`

---

### T5: Admissão atômica de dois orçamentos (Lua único) ✅

**What**: Substituir os dois `tryAcquire` sequenciais pelo script de dois contadores com rollback (design §3); fallback degradado aplica a fatia por instância aos dois orçamentos.
**Where**: `payment-api/src/main/java/com/example/payments/api/ratelimit/RedisRateLimiter.java`
**Depends on**: None
**Reuses**: `AdmissionControlIT` (harness), Lua existente
**Requirement**: AUD-05

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] IT: tenant estourado NÃO consome o orçamento de rota — outro tenant continua sendo admitido até o limite da rota (o cenário exato que o teste antigo não discriminava)
- [x] IT degradado (Redis parado): os dois orçamentos aplicam a fatia local
- [x] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-api): atomic dual-budget admission with route-token rollback`

---

### T6: Tenant anônimo fixo na rota /v0 ✅

**What**: `ConcurrencyLimitFilter` usa `ANONYMOUS_TENANT` quando o path é `/v0/...` — `X-API-Key` arbitrária deixa de criar bucket novo por request.
**Where**: `payment-api/src/main/java/com/example/payments/api/filter/ConcurrencyLimitFilter.java`
**Depends on**: T5
**Reuses**: `AdmissionControlIT` v0 tests
**Requirement**: AUD-05

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] IT: rajada no /v0 com `X-API-Key` rotativa cai toda no mesmo bucket anônimo (429 no limite de tenant, não bypass)
- [x] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-api): rotating keys cannot mint tenant buckets on the anonymous route`

---

### T7: Waiter removido em toda saída ✅

**What**: `publishAndAwait` garante `unregister` por try/finally em qualquer exceção entre `register()` e `await()` (hoje só o caminho de falha de publish limpa).
**Where**: `payment-api/src/main/java/com/example/payments/api/service/ApiPaymentService.java`
**Depends on**: None
**Reuses**: `ApiPaymentServiceUnitTest` (mocks já lançam `StoreUnavailableException`)
**Requirement**: AUD-06

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] Unit: `markPublishState`/`save`/`completeFromStore` lançando após register → waiter removido (asserção no tamanho do mapa/`api_pending`)
- [x] Caminhos existentes inalterados (suite verde)
- [x] Gate Quick passa

**Tests**: unit
**Gate**: quick

**Commit**: `fix(payment-api): unregister the waiter on every exit path`

---

### T8: Leak de conexão pub/sub + escaping do fingerprint ✅

**What**: `trySubscribe` fecha a conexão recém-aberta quando `subscribe()` falha; `IdempotencyFingerprint` escapa `|`/`\` nos campos antes do join.
**Where**: `payment-api/src/main/java/com/example/payments/api/coordination/ResponseCoordinator.java`
**Depends on**: None
**Reuses**: `IdempotencyFingerprintUnitTest`
**Requirement**: AUD-17, AUD-18

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [x] Unit: os dois payloads que hoje colidem (`pm|X`+`1` vs `pm`+`X|1`) produzem fingerprints distintos
- [x] Unit: subscribe falhando → conexão criada é fechada (verify no mock)
- [x] Gate Quick passa

**Tests**: unit
**Gate**: quick

**Commit**: `fix(payment-api): close orphaned pubsub connections and escape fingerprint fields`

---

### T9: paymentMethod limitado + remoção da superfície topic-ab

**What**: `@Pattern("[A-Z_]{2,32}")` em `paymentMethod` (e `brand`); colapso para `"other"` na métrica acima de 50 valores; remover a flag `payment-topic-ab`, o header `X-Routed-Topic` e o uso de `TopicRouter` no v0 (anuncia roteamento que não acontece).
**Where**: `payment-api/src/main/java/com/example/payments/api/controller/V0PaymentSimulationController.java`
**Depends on**: None
**Reuses**: `ApiMetrics`, validação Bean existente
**Requirement**: AUD-16, AUD-27

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: `paymentMethod` fora do padrão → 400 problem+json
- [ ] Unit: 51º valor distinto vira série `"other"`
- [ ] `X-Routed-Topic` ausente da resposta v0; flag removida do YAML e docs
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-api): bound metric cardinality and drop the fake topic routing surface`

---

### T10: Fingerprint no idempotency_record do SBUS

**What**: Migration `V10` (coluna `fingerprint` nullable); `persistRequested` grava fingerprint canônico calculado do payload; `findReplayTarget` compara — divergente ou legado-null ⇒ não é replay, simulação nova (design §5).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/service/PaymentPersistenceService.java`
**Depends on**: None
**Reuses**: algoritmo canônico da API (portado), `IdempotencyReplayIT`
**Requirement**: AUD-01

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: mesma chave + payload divergente após a janela da API → simulação NOVA com resultado próprio (o cenário de valor errado do F1)
- [ ] IT: mesma chave + payload idêntico → replay como hoje
- [ ] IT: linha legada com fingerprint null → tratada como não-replay
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): replay resolution verifies the payload fingerprint`

---

### T11: Replay não fica stranded PROCESSING

**What**: `registerReplayInFlight` re-verifica o estado da original dentro da transação; se já terminal, sinaliza ao caller para resolver como replay terminal (loop de re-resolução em `resolveReplay`).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/service/PaymentSimulationService.java`
**Depends on**: T10
**Reuses**: `IdempotencyReplayIT`
**Requirement**: AUD-11

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT que interleava: resposta do Core lida → replay registrado → finalização; o replay termina COMPLETED (não fica PROCESSING órfão)
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): a replay racing the core response is never stranded`

---

### T12: Renovação de lease por linha no dispatcher

**What**: A cada linha publicada, renovar `claimed_at` das linhas restantes do claim (`UPDATE ... WHERE claim_token = :token AND status='IN_PROGRESS'`) — lote lento não excede o lease por construção (design AUD-07).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxDispatcher.java`
**Depends on**: None
**Reuses**: `OutboxBatchResilienceIT`
**Requirement**: AUD-07

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT com lease curto: lote lento (latência injetada por linha) completa sem nenhuma linha reapeada/republicada
- [ ] Fence perdido ainda interrompe o lote com métrica (comportamento existente preservado)
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): slow batches renew their claim instead of outliving it`

---

### T13: Reaper com attempts, backoff e LIMIT

**What**: O UPDATE do reaper passa a `attempts = attempts + 1`, `next_attempt_at = now + backoff(attempts)` e `LIMIT` em lote — linha sempre-reclamada alcança `max-attempts` e DLQ em vez de loop quente.
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/repository/OutboxEventRepository.java`
**Depends on**: T12
**Reuses**: `BackoffCalculator`, `RecoverableDeadLetterIT`
**Requirement**: AUD-08

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: linha cujo publisher morre repetidamente acumula attempts pelo reaper e termina `DLQ_PENDING` (hoje: loop infinito sem backoff)
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): reaped rows accumulate attempts and reach the DLQ`

---

### T14: HealthIndicators reais + classificação do Registry

**What**: Um `HealthIndicator` de readiness por dependência (`readiness-required`) executando os budgets declarados (design §6); `SimulationMessageHandler` separa indisponibilidade do Registry (transitória → retry) de payload indecodificável (poison).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/health/` (novo pacote)
**Depends on**: None
**Reuses**: `DependencyPolicies` (hoje sem leitor de produção)
**Requirement**: AUD-09

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: Registry parado → readiness DOWN e registro consumido vai para retry, não para DLQ (hoje: readiness UP e pagamento válido dead-letterado)
- [ ] IT: Postgres parado → readiness DOWN
- [ ] Payload realmente indecodificável continua poison → DLQ
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): readiness reflects declared dependencies and registry outages retry`

---

### T15: Grupos separados + max.poll.interval

**What**: `payment-sbus-requested` / `payment-sbus-core-response` como groupIds; `max.poll.interval.ms: 2100000` no consumer default; IT provando releitura idempotente do histórico ANTES do rename (design §4).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/kafka/PaymentRequestedConsumer.java`
**Depends on**: None
**Reuses**: camadas de idempotência existentes
**Requirement**: AUD-10

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: processar o mesmo registro Requested duas vezes → segunda é no-op; resposta p/ simulação terminal → ignorada (prova da releitura segura)
- [ ] groupIds distintos nos dois consumers; `max.poll.interval.ms` > orçamento de retry (asserção por reflexão como em `ConsumerErrorStrategyUnitTest`)
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): isolate consumer groups and fit max.poll to the retry budget`

---

### T16: Higiene do SBUS (bundle coeso)

**What**: `initialDelay` de housekeeping configurável; purga `PUBLISHED` com `LIMIT`; índice para o claim `PENDING` (`V11`); evento de replay terminal reescreve `requestId` dentro do payload Avro (não só no envelope).
**Where**: `payment-sbus/src/main/java/com/example/payments/sbus/outbox/OutboxHousekeeping.java`
**Depends on**: T13
**Reuses**: `IdempotencyReplayIT` (asserção do payload), migrations existentes
**Requirement**: AUD-23, AUD-24, AUD-25, AUD-27

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] `initialDelay` vem de propriedade; purga em lotes com LIMIT; `V11` cria o índice
- [ ] IT do replay assere `result.requestId == replayRequestId` no payload retornado (hoje codifica o bug)
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(payment-sbus): housekeeping knobs, bounded purge, claim index, honest replay payload`

---

### T17: Transições de status do async-redis viram CAS Lua

**What**: `ENQUEUE_FAILED → PROCESSING` só via CAS (um vencedor); `markEnqueueFailed` condicionado a `PROCESSING` (nunca sobrescreve terminal); documentar a janela residual (design §7).
**Where**: `async-redis-service/src/main/java/com/example/platform/asyncredis/api/JobStatusStore.java`
**Depends on**: None
**Reuses**: `JobAcceptanceServiceEnqueueFailureUnitTest`, padrão Lua do `ResultReleaser`
**Requirement**: AUD-03

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT concorrente: dois replays do mesmo `ENQUEUE_FAILED` → exatamente um XADD (contagem no stream)
- [ ] IT: `markEnqueueFailed` após release do worker → `COMPLETED` preservado, `GET` retorna o resultado
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(async-redis): status transitions are compare-and-set, not check-then-act`

---

### T18: Estado FAILED terminal para jobs dead-letterados

**What**: `JobState.FAILED` + `JobStatusView.Failed`; os dois caminhos de DLQ marcam `FAILED` (XX condicionado a `PROCESSING`); `GET` → `200` com `status:"FAILED"`.
**Where**: `async-redis-service/src/main/java/com/example/platform/asyncredis/worker/JobWorker.java`
**Depends on**: T17
**Reuses**: switch exaustivo do controller (força os call sites)
**Requirement**: AUD-13

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: job envenenado → DLQ → `GET` retorna `FAILED` (hoje: 202 por 24h e depois 404)
- [ ] DLQ-antes-do-XACK preservado nos dois caminhos
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(async-redis): dead-lettered jobs get an observable terminal state`

---

### T19: Renovação de lease durante o scan de reclaim

**What**: O scan renova o turno a cada entrada processada; renovação falhou → aborta o scan (design AUD-12).
**Where**: `async-redis-service/src/main/java/com/example/platform/asyncredis/worker/JobWorker.java`
**Depends on**: None
**Reuses**: `ReclaimCoordinator.renewTurn` (fencing já correto)
**Requirement**: AUD-12

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] IT: scan mais longo que o lease com renovação → nenhum segundo worker toma o turno (delivery count não infla)
- [ ] IT: renovação negada → scan aborta sem processar o restante
- [ ] Gate Full passa

**Tests**: integration
**Gate**: full

**Commit**: `fix(async-redis): the reclaim scan renews the lease it runs under`

---

### T20: Higiene do async-redis

**What**: Fechar a conexão shared antiga ao recriar; validação de startup `status-ttl >= idempotency-ttl`.
**Where**: `async-redis-service/src/main/java/com/example/platform/asyncredis/redis/RedisConnections.java`
**Depends on**: None
**Reuses**: validações existentes de `AsyncRedisProperties`
**Requirement**: AUD-19, AUD-20

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] Unit: recriação fecha a anterior; config com `status-ttl < idempotency-ttl` recusa boot
- [ ] Gate Quick passa

**Tests**: unit
**Gate**: quick

**Commit**: `fix(async-redis): close replaced connections and validate the ttl ordering`

---

### T21: AD-007 + recalibração de configuração

**What**: Registrar AD-007 em `.specs/STATE.md` (supersede AD-006); `limit-for-period: 20`, `tenant-limit-for-period: 10` no YAML da API; docs de configuração/segurança/performance atualizados nas fronteiras tocadas.
**Where**: `payment-api/src/main/resources/application.yml`
**Depends on**: None
**Reuses**: gate de drift de configuração (falha sozinho se doc divergir)
**Requirement**: AUD-30

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] AD-007 registrado com status active e AD-006 marcado superseded
- [ ] Limites novos no YAML + docs; gates de docs passam
- [ ] Gate Build passa

**Tests**: none
**Gate**: build

**Commit**: `feat(workspace): recalibrate capacity targets to 1000 req/min (AD-007)`

---

### T22: Gate de capacidade honesto

**What**: k6 com 2 tenants (~8,5/s cada); thresholds `avg<300` e `p(99)<10000` em `http_req_duration`; veredito do relatório reprova com `429 > 1%` do steady — mix de status entra no veredito.
**Where**: `load/k6/capacity.js`
**Depends on**: T21
**Reuses**: `load/capacity/generate_report.py`, `lib.sh`
**Requirement**: AUD-30

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] k6 distribui por 2 API keys; thresholds ativos
- [ ] `generate_report.py` reprova por mix (verificado com fixture de mix ruim)
- [ ] DRYRUN do cenário passa
- [ ] Gate Build passa

**Tests**: none
**Gate**: build

**Commit**: `fix(gates): capacity verdict measures the system, not one tenant bucket`

---

### T23: Revogação + execução ao vivo do gate de capacidade

**What**: Marcar o relatório de 2026-08-12 como REVOGADO no CAP-02 (mediu o limiter de tenant); reconstruir imagens com as correções; rodar o cenário completo (15 min steady + spike) ao vivo; relatório novo datado.
**Where**: `.specs/features/repository-segregation-production-hardening/spec.md`
**Depends on**: T22
**Reuses**: `load/capacity/scenarios/steady.sh`, stack do sandbox
**Requirement**: AUD-30

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] Relatório novo: 1.000 req/min sustentado, `429 ≤ 1%`, média ≤ 300ms, p99 ≤ 10s, 0 erro técnico
- [ ] CAP-02 aponta para a evidência nova e marca a anterior revogada
- [ ] Gate ao vivo passa

**Tests**: none
**Gate**: build

**Commit**: `docs(state): revoke the tenant-bucket capacity report and certify the real target`

---

### T24: Verificação final do workspace

**What**: `scripts/verify-workspace.sh` completo (8 estágios) com todas as imagens reconstruídas; equivalence regenerado; traceability do spec desta feature toda `Verified`; handoff atualizado.
**Where**: `.specs/features/adversarial-audit-fixes/spec.md`
**Depends on**: T23
**Reuses**: gates existentes
**Requirement**: AUD-30

**Tools**: MCP: NONE · Skill: NONE

**Done when**:
- [ ] 8/8 estágios PASS ao vivo
- [ ] Suítes das 4 fronteiras + feature-control verdes (baselines só cresceram)
- [ ] Equivalence PASS; traceability atualizada
- [ ] Gate Build passa

**Tests**: none
**Gate**: build

**Commit**: `docs(state): close the adversarial audit with full workspace evidence`

---

## Phase Execution Map

As fases rodam em sequência (1 → 2 → 3 → 4 → 5). Dentro de cada fase, as cadeias com seta nos
diagramas acima são a dependência real; o restante é independente e executa em ordem numérica.

24 tarefas → ~4 batches de fases inteiras (P1: 4, P2: 5, P3: 7, P4+P5: 8). **Acima do orçamento de um batch (~8): oferta de sub-agentes obrigatória antes do Execute.** Verifier sempre roda ao final.

---

## Task Granularity Check

| Task | Scope | Status |
| --- | --- | --- |
| T1–T4 | 1 arquivo principal cada (+ testes) | ✅ Granular |
| T5–T9 | 1 arquivo principal cada | ✅ Granular |
| T10–T16 | 1 arquivo principal cada; T16 é bundle coeso de higiene do mesmo subsistema | ✅ Granular |
| T17–T20 | 1 arquivo principal cada | ✅ Granular |
| T21–T24 | config/evidência, 1 entregável cada | ✅ Granular |

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | início Phase 1 | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | None | nó isolado | ✅ Match |
| T4 | T2 | T2 → T4 | ✅ Match |
| T5 | None | início Phase 2 | ✅ Match |
| T6 | T5 | T5 → T6 | ✅ Match |
| T7, T8, T9 | None | nós isolados | ✅ Match |
| T10 | None | início Phase 3 | ✅ Match |
| T11 | T10 | T10 → T11 | ✅ Match |
| T12 | None | início de cadeia | ✅ Match |
| T13 | T12 | T12 → T13 | ✅ Match |
| T14, T15 | None | nós isolados | ✅ Match |
| T16 | T13 | T13 → T16 | ✅ Match |
| T17 | None | início Phase 4 | ✅ Match |
| T18 | T17 | T17 → T18 | ✅ Match |
| T19, T20 | None | nós isolados | ✅ Match |
| T21 | None | início Phase 5 | ✅ Match |
| T22 | T21 | T21 → T22 | ✅ Match |
| T23 | T22 | T22 → T23 | ✅ Match |
| T24 | T23 | T23 → T24 | ✅ Match |

Nenhuma dependência aponta para fase posterior.

---

## Test Co-location Validation

| Task | Code Layer | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1, T2, T4 | Store Redis | integration | integration | ✅ OK |
| T3 | Resolver (domínio) | unit | unit | ✅ OK |
| T5, T6 | Store/filtro com Redis real | integration | integration | ✅ OK |
| T7, T8 | Serviço/coordenação | unit | unit | ✅ OK |
| T9 | Controller/rota | integration | integration | ✅ OK |
| T10–T15 | Consumer/repositório | integration | integration | ✅ OK |
| T16 | Repositório + migration | integration | integration | ✅ OK |
| T17–T19 | Worker/store | integration | integration | ✅ OK |
| T20 | Config/conexão | unit | unit | ✅ OK |
| T21–T24 | Config/script de gate/evidência | none | none | ✅ OK (verificados pela execução ao vivo) |

Nenhum `Tests: none` é diferimento: T21–T24 não são camadas de código Java e são provados pelo gate ao vivo da Fase 5.
