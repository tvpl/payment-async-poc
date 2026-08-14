# Adversarial Audit Fixes Specification

## Problem Statement

Uma auditoria adversarial de 4 dimensões (payment-api, payment-sbus, async-redis + feature-control, coerência cross-boundary) em 2026-08-13 produziu 25+ achados verificados contra o código, incluindo 2 críticos, e invalidou a certificação de capacidade vigente: o relatório CAP-02 marcado PASS mediu na verdade o rate limiter de tenant (50/s admitidos, 70% de 429), não a capacidade do sistema — o k6 usa uma única API key contra um bucket de tenant de 50/s.

Junto com isso, o usuário recalibrou os alvos do produto: a meta de 10.000 req/min (AD-006) está alta demais para o propósito real — o alvo passa a ser **1.000 req/min**, com latência média esperada de **~300ms** e teto duro de **10s** por requisição.

## Goals

- [ ] Nenhum achado crítico ou major da auditoria permanece aberto sem correção ou decisão registrada.
- [ ] AD-007 registrado, superseding AD-006: 1.000 req/min sustentado, média ≤ 300ms, p99 ≤ 10s.
- [ ] Gate de capacidade re-executado ao vivo contra os novos alvos, com relatório datado que mede o que afirma medir (mix de status visível no veredito, não só erro técnico).
- [ ] O kill-switch do feature-control nunca desarma por indisponibilidade do Redis.
- [ ] Nenhum caminho onde um pagamento com payload divergente reusa o resultado de outro.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Redis HA (Sentinel/Cluster) em teste | Gap conhecido e documentado como não-comprovado; exige infraestrutura própria, não é achado desta auditoria |
| TLS/SASL nos listeners locais | Checklist de deploy documentado; sandbox local é texto plano por decisão |
| Consumo real do tópico `payment.simulation.requested.v0` | A superfície enganosa será removida (AUD-27); criar o tópico + consumidor é feature nova, não correção |
| Refatorar `coordination/` da API em dois pacotes | Observação de estrutura, não defeito |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Novo alvo de capacidade | 1.000 req/min sustentado por 15 min; spike 2.000 req/min por 60s | Decisão do usuário ("pode ser 1000 por min"); spike mantém a proporção 2× de AD-006 | y |
| Alvo de latência | média ≤ 300ms; p99 ≤ 10s (duro) | Decisão do usuário; `wait-timeout: 3s` permanece — já garante o teto síncrono bem abaixo de 10s | y |
| Novos limites de admissão | rota `20/s`, tenant `10/s` (janela 1s) | 20/s = 1.200/min, 20% de folga sobre o alvo; tenant 10/s preserva a proteção noisy-neighbor (metade da rota), e o gate passa a usar 2 tenants | n — default logado |
| Limite do Core (`sbus.core.limit-for-period: 50`) | inalterado | Com alvo de 17/s, 50/s passa a ter 3× de folga — o desbalanceamento F4 (200/s admitido vs 50/s drenado) se resolve pela recalibração da entrada, não mexendo no Core | y (consequência aritmética) |
| Gate de capacidade multi-tenant | k6 com 2 API keys, ~8,5/s cada | Único jeito de medir capacidade do sistema em vez do bucket de um tenant; o defeito F3 foi exatamente esse | n — default logado |
| Veredito do gate exige mix de status saudável | `429 ≤ 1%` do total em steady (além de erro técnico 0) | Um PASS com 70% de 429 nunca mais pode acontecer; em regime dentro do alvo, 429 é anomalia | n — default logado |
| Semântica de replay com payload divergente (AUD-01) | fingerprint difere ⇒ tratar como simulação NOVA (não copiar resultado, não é conflito) | `Idempotency-Key` deduplica operações *idênticas*; payload diferente é operação diferente. Dentro de 15m a API já dá 409; após, o SBUS processa como novo — nunca entrega o resultado de outro valor | n — default logado |
| Kill-switch em cold start com Redis fora (AUD-02) | não armado (latch em memória só preserva estado dentro do processo); `master-enabled: false` estático no YAML é o break-glass de cold start | Sem storage local não há como saber o estado dinâmico; a alternativa (fail-killed no boot) tornaria qualquer boot sem Redis um outage total auto-infligido | n — default logado |
| Grupos Kafka do SBUS (AUD-10) | separar `payment-sbus` em `payment-sbus-requested` e `payment-sbus-core-response` + `max.poll.interval.ms: 35m` | Grupos novos com `offsetReset: EARLIEST` relerão o histórico do tópico — seguro por construção: `request_id UNIQUE` torna replay de Requested no-op e resposta p/ simulação terminal é ignorada | n — default logado |
| Job dead-letter no async-redis (AUD-13) | novo `JobState.FAILED`; `GET` retorna `200` com `status: "FAILED"` | O cliente precisa de um terminal observável; 200-com-FAILED segue o padrão do fluxo principal (422 é só no caminho síncrono da API de pagamento, aqui o contrato é status no corpo) | n — default logado |
| Superfície `payment-topic-ab` (AUD-27) | remover a flag e o header `X-Routed-Topic` | O header anuncia um roteamento que não acontece (o publish é sempre `Topics.REQUESTED`) e o tópico `.v0` não existe no contrato nem tem consumidor — manter é anunciar mentira; wire de verdade está fora de escopo | n — default logado |
| Cardinalidade de `paymentMethod` (AUD-16) | validação `@Pattern` no request (`[A-Z_]{2,32}`) + colapso para `"other"` na métrica acima de 50 valores distintos | Duas defesas: contrato rejeita lixo com 400; a métrica fica limitada mesmo se a validação mudar | n — default logado |

**Open questions:** nenhuma bloqueante — os defaults acima regem a implementação salvo instrução em contrário; os quatro primeiros já são decisão explícita do usuário.

---

## User Stories

### P1: Nenhum caminho de dinheiro errado ou segurança furada ⭐ MVP

**User Story**: Como dono do produto, quero que nenhum pagamento possa receber o resultado de outro, que o kill-switch funcione exatamente quando a infraestrutura está pior, e que a admissão não seja contornável — porque esses são os defeitos que custam dinheiro ou controle.

**Why P1**: São os achados críticos e os majors com efeito de correção financeira/segurança.

**Acceptance Criteria**:

1. IF uma requisição chega com `Idempotency-Key` já conhecida pelo SBUS mas com fingerprint de payload divergente THEN o SBUS SHALL processá-la como simulação nova, e SHALL NOT copiar o resultado da original. <!-- unwanted-behavior / AUD-01 -->
2. WHILE o Redis do feature-control está indisponível, WHEN o kill-switch estava armado na última leitura bem-sucedida o `MasterSwitch` SHALL continuar reportando armado (latch em memória), independentemente de `max-stale` e `stale-fallback`. <!-- complex / AUD-02 -->
3. WHILE o Redis do feature-control está indisponível, o `RedisFlagSource` SHALL servir a política de stale sem adquirir o lock de single-flight em toda chamada — falhas SHALL abrir uma janela de backoff (≥1s) em que leituras não tocam o Redis. <!-- state-driven / AUD-14 -->
4. WHEN um flag `VARIANT` seleciona a variante configurada como `off-variant` THEN `FeatureDecision.isOn()` SHALL retornar `false`. <!-- event-driven / AUD-04 -->
5. WHEN uma requisição é negada pelo orçamento de tenant THEN o token do orçamento de rota que ela consumiria SHALL ser devolvido — os dois orçamentos SHALL ser avaliados atomicamente (um `EVAL`). <!-- event-driven / AUD-05 -->
6. WHEN uma requisição chega em `/v0/payment-simulations` THEN o bucket de tenant usado SHALL ser o de anônimo fixo, ignorando qualquer `X-API-Key` não validada. <!-- event-driven / AUD-05 -->
7. IF a aceitação de um job async-redis encontra `ENQUEUE_FAILED` num replay THEN a transição `ENQUEUE_FAILED → PROCESSING` SHALL ser um CAS atômico (Lua); um segundo replay concorrente SHALL perder o CAS e receber `Replay` sem enfileirar. <!-- unwanted-behavior / AUD-03 -->
8. IF `markEnqueueFailed` executa após o worker já ter completado o job THEN o status terminal SHALL ser preservado (escrita condicionada a `PROCESSING`). <!-- unwanted-behavior / AUD-03 -->

**Independent Test**: cada AC tem teste próprio; o do AC-1 injeta a mesma chave com payload divergente após expirar a reserva da API e assere que a segunda simulação tem `simulationId` próprio e resultado próprio.

---

### P2: Disponibilidade sob falha real

**User Story**: Como operador, quero que quedas de dependência degradem do jeito documentado — sem vazamento de recursos, sem loop quente, sem readiness mentirosa, sem pagamento válido indo para a DLQ por causa de um restart do Registry.

**Why P2**: São os majors de disponibilidade: cada um transforma uma falha rotineira de infraestrutura em incidente.

**Acceptance Criteria**:

1. IF o Redis falha depois de `ResponseCoordinator.register()` e antes de `await()` THEN o waiter SHALL ser removido em toda saída (try/finally) — o mapa de waiters SHALL voltar ao tamanho anterior. <!-- unwanted-behavior / AUD-06 -->
2. WHEN o `OutboxDispatcher` publica cada linha do lote THEN o lease das linhas restantes do claim SHALL ser renovado, de modo que um lote lento não exceda o lease por construção. <!-- event-driven / AUD-07 -->
3. WHEN o `OutboxReaper` devolve uma linha para `PENDING` THEN a linha SHALL receber `attempts + 1` e `next_attempt_at` com backoff — uma linha reclamada repetidamente SHALL alcançar `max-attempts` e o caminho de DLQ. <!-- event-driven / AUD-08 -->
4. O `payment-sbus` SHALL expor um `HealthIndicator` de readiness por dependência declarada com `readiness-required: true` (Kafka, PostgreSQL, Redis, Registry). <!-- ubiquitous / AUD-09 -->
5. IF a deserialização Avro falha por indisponibilidade do Registry (erro de conectividade) THEN o registro SHALL ser tratado como falha transitória (retry), e SHALL NOT ser classificado como poison. <!-- unwanted-behavior / AUD-09 -->
6. Os consumers de `payment.simulation.requested` e `payment.simulation.core.response` SHALL usar consumer groups distintos, e `max.poll.interval.ms` SHALL exceder o orçamento de retry do `@ErrorStrategy` (30 min). <!-- ubiquitous / AUD-10 -->
7. IF um replay é registrado enquanto a resposta do Core está sendo aplicada THEN o registro SHALL re-verificar o estado da original dentro da transação e, se já terminal, resolver como replay terminal — nenhuma linha SHALL ficar `PROCESSING` sem resposta pendente. <!-- unwanted-behavior / AUD-11 -->
8. WHILE o scan de reclaim do async-redis processa entradas pendentes, o lease do turno SHALL ser renovado a cada entrada; se a renovação falhar o scan SHALL abortar. <!-- state-driven / AUD-12 -->
9. WHEN um job async-redis é roteado à DLQ THEN seu status SHALL transicionar para `FAILED` (novo estado terminal), e `GET /jobs/{id}` SHALL retornar `200` com `status: "FAILED"`. <!-- event-driven / AUD-13 -->

**Independent Test**: cada AC com teste que induz a falha real (Redis parado, lote lento com lease curto, Registry derrubado durante consumo, replay concorrente com resposta do Core).

---

### P3: Recalibração de capacidade e evidência honesta

**User Story**: Como dono do produto, quero os alvos que fazem sentido para o sistema real — 1.000 req/min, ~300ms de média, teto de 10s — e um gate que meça isso de verdade, para que um PASS volte a significar alguma coisa.

**Why P3**: A certificação vigente é inválida; os números novos são decisão de produto já tomada.

**Acceptance Criteria**:

1. O `.specs/STATE.md` SHALL registrar AD-007 superseding AD-006 com os alvos: 1.000 req/min sustentado 15 min, spike 2.000 req/min 60s, média ≤ 300ms, p99 ≤ 10s. <!-- ubiquitous / AUD-30 -->
2. WHEN a admissão é recalibrada THEN a rota SHALL admitir 20/s e cada tenant 10/s (janela 1s), com os docs de configuração atualizados. <!-- event-driven / AUD-30 -->
3. WHEN o gate de capacidade roda THEN o k6 SHALL distribuir a carga entre ≥2 tenants e o veredito SHALL reprovar se `429 > 1%` do total em steady, além de erro técnico. <!-- event-driven / AUD-30 -->
4. WHEN o gate de capacidade roda no alvo THEN a duração HTTP SHALL ter média ≤ 300ms e p99 ≤ 10s, asseridos como thresholds do k6. <!-- event-driven / AUD-30 -->
5. WHEN o gate conclui THEN o relatório datado SHALL exibir o mix de status no veredito e o spec CAP-02 SHALL apontar para a nova evidência, revogando a anterior. <!-- event-driven / AUD-30 -->

**Independent Test**: rodar `scripts/verify-workspace.sh` + o cenário de capacidade completo ao vivo; o relatório novo substitui o inválido.

---

### P4: Higiene — menores da auditoria

**User Story**: Como mantenedor, quero os achados menores fechados no mesmo passe, para que a auditoria não deixe cauda.

**Why P4**: Baratos, verificados, e cada um é um incidente pequeno esperando volume.

**Acceptance Criteria**:

1. IF `subscribe()` do pub/sub falha após `connectPubSub()` THEN a conexão recém-aberta SHALL ser fechada antes do reagendamento. <!-- unwanted-behavior / AUD-17 -->
2. Os campos do fingerprint de idempotência SHALL ser escapados antes do join com `|`, de modo que payloads distintos SHALL NOT colidir por injeção de delimitador. <!-- ubiquitous / AUD-18 -->
3. IF a conexão shared do async-redis está fechada WHEN uma nova é criada THEN a antiga SHALL ser fechada explicitamente. <!-- unwanted-behavior / AUD-19 -->
4. A validação de startup do async-redis SHALL exigir `status-ttl >= idempotency-ttl`. <!-- ubiquitous / AUD-20 -->
5. WHEN um `DELETE` de flag inexistente executa THEN a auditoria SHALL registrar `result: "noop"`, não `"ok"`. <!-- event-driven / AUD-21 -->
6. IF todos os pesos de um `VARIANT` somam zero THEN a seleção SHALL resolver off, e SHALL NOT retornar a primeira variante. <!-- unwanted-behavior / AUD-22 -->
7. Os `initialDelay` de housekeeping do SBUS SHALL ser configuráveis, com default documentado. <!-- ubiquitous / AUD-23 -->
8. A purga de `PUBLISHED` da outbox SHALL usar `LIMIT` em lotes como as demais purgas. <!-- ubiquitous / AUD-24 -->
9. A query de claim SHALL ter índice cobrindo o caminho `PENDING` com a ordenação usada. <!-- ubiquitous / AUD-25 -->
10. WHEN uma invalidação de flag chega durante um refresh em andamento THEN o valor pré-escrita SHALL NOT ser re-assentado no cache (guarda de geração). <!-- unwanted-behavior / AUD-26 -->
11. A flag `payment-topic-ab` e o header `X-Routed-Topic` SHALL ser removidos; o evento de replay terminal SHALL reescrever o `requestId` também dentro do payload Avro. <!-- ubiquitous / AUD-27 + payload replay -->
12. `paymentMethod` SHALL ser validado por padrão restrito no request e a métrica SHALL colapsar valores acima do limite de cardinalidade para `"other"`. <!-- ubiquitous / AUD-16 -->

---

## Edge Cases

- IF o processo reinicia durante uma queda do Redis com o kill-switch armado THEN o latch se perde — o comportamento é o documentado: baseline YAML é o break-glass; o restart não re-arma sozinho.
- IF dois replays do mesmo `ENQUEUE_FAILED` chegam no mesmo milissegundo THEN exatamente um vence o CAS e enfileira; o outro recebe `Replay`.
- WHEN os grupos Kafka novos leem o histórico do tópico pela primeira vez THEN todo registro já processado resolve como no-op pelas camadas de idempotência (comprovado por teste antes do rename).
- IF a renovação de lease do outbox falha no meio do lote THEN o dispatcher para o lote; linhas não publicadas voltam pelo reaper com attempts+backoff (AC P2-3).
- WHEN a carga está exatamente no alvo (17/s, 2 tenants) THEN nenhum 429 é esperado; o limiar de 1% cobre rajada de arredondamento de janela fixa.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| AUD-01 | P1: replay com payload divergente | T10 | Verified — `3405efc` |
| AUD-02 | P1: kill-switch latch | T1 | Verified — `5f1d23f` |
| AUD-03 | P1: ENQUEUE_FAILED CAS | T17 | Verified — `274b0ca` |
| AUD-04 | P1: VARIANT isOn | T3 | Verified — `5f17e3d` |
| AUD-05 | P1: admissão atômica + v0 anônimo | T5, T6 | Verified — `0a5ccc6`, `3b514ac` |
| AUD-14 | P1: backoff de falha no flag source | T2 | Verified — `83e93c5` |
| AUD-06 | P2: waiter leak | T7 | Verified — `c983e49` |
| AUD-07 | P2: renovação de lease do lote | T12 | Verified — `d038e59` |
| AUD-08 | P2: reaper com attempts+backoff | T13 | Verified — `0bdb2de` |
| AUD-09 | P2: readiness real + classificação Registry | T14 | Verified — `b995398` |
| AUD-10 | P2: grupos separados + max.poll | T15 | Verified — `1f1d3d6` |
| AUD-11 | P2: replay stranded | T11 | Verified — `68bb73f` |
| AUD-12 | P2: renovação de lease do reclaim | T19 | Verified — `b8d8b77` |
| AUD-13 | P2: estado FAILED no async-redis | T18 | Verified — `2a32492` |
| AUD-30 | P3: recalibração + gate honesto | T21, T22, T23 | Verified — `28ce946`, `d00da78`, `5b5d6fe` (live: `load/reports/20260814-123447-capacity-report.md`) |
| AUD-16 | P4: cardinalidade paymentMethod | T9 | Verified — `9c5ab4f` |
| AUD-17 | P4: leak conexão pub/sub | T8 | Verified — `b6fefcc` |
| AUD-18 | P4: escaping do fingerprint | T8 | Verified — `b6fefcc` |
| AUD-19 | P4: leak conexão shared async | T20 | Verified — `bf66337` |
| AUD-20 | P4: validação status-ttl >= idempotency-ttl | T20 | Verified — `bf66337`, `434df7f` |
| AUD-21 | P4: auditoria noop em delete inexistente | T4 | Verified — `ea4961e` |
| AUD-22 | P4: variant peso zero resolve off | T3 | Verified — `5f17e3d` |
| AUD-23 | P4: initialDelay configurável | T16 | Verified — `a12a0cb` |
| AUD-24 | P4: purga com LIMIT | T16 | Verified — `a12a0cb` |
| AUD-25 | P4: índice do claim PENDING | T16 | Verified — `a12a0cb` |
| AUD-26 | P4: guarda de geração no cache | T4 | Verified — `ea4961e` |
| AUD-27 | P4: remover superfície topic-ab + payload do replay | T9, T16 | Verified — `9c5ab4f`, `a12a0cb` |

**ID format:** `AUD-[NUMBER]`

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 27 total, 27 mapeados para tarefas, 27 verificados, 0 não mapeados

---

## Success Criteria

- [x] Suítes completas verdes nas 4 fronteiras tocadas + feature-control (baseline só cresce). Cada tarefa (T1-T20) passou seu próprio gate Full/Quick ao vivo no momento do commit (git log `5f1d23f`..`bf66337`); `equivalence verify` (T24) confirma que a contagem só cresceu (sources 204→211, tests 115→127, test_cases 535→592, migrations 9→11).
- [x] Gate de capacidade ao vivo: 1.000 req/min por 15 min, 2 tenants, `429 ≤ 1%`, média ≤ 300ms, p99 ≤ 10s, 0 erro técnico. `load/reports/20260814-123447-capacity-report.md`: 17,00 req/s × 15min, `429=0`, erro técnico 0,0000%, latência média 293,69ms, p99 421,65ms.
- [x] `scripts/verify-workspace.sh` 8/8 com as imagens reconstruídas. Rodou ao vivo (T24) após a recuperação de uma indisponibilidade real do Docker Desktop (disco do host cheio — ver Handoff em `.specs/STATE.md`): equivalence, no-composite-build, artifact-only-consumer, e2e-payment, e2e-async-redis, payment-failures (11/11), async-redis-failures (34/34), hygiene — todos PASS. O primeiro run ao vivo achou um bug real e não relacionado ao Docker: o fixture RED-01 nunca setava `ASYNC_REDIS_IDEMPOTENCY_TTL`, então o guard do AUD-20 (T20) recusava corretamente o container; corrigido no fixture, não no guard.
- [x] Kill-switch comprovado armado durante queda real do Redis (teste de integração). T1 (`5f1d23f`): IT com Redis real parado prova o latch armado durante e após `max-stale`.
- [x] Relatório CAP-02 anterior marcado como revogado no spec de origem, apontando para o novo. `repository-segregation-production-hardening/spec.md` CAP-02 (T23, `5b5d6fe`).
