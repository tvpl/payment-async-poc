# Revisão de arquitetura — Sync-to-Async em escala bancária (2026-08)

Revisão feita com a lente de um deploy bancário de grande porte (camada de
abstração entre canais externos e serviços internos). **Nenhum código de
aplicação foi alterado por esta revisão** — este documento registra
oportunidades reais, o porquê de cada uma, e a ordem em que atacá-las. A
entrega de código associada foi apenas a fronteira [`gateway`](../gateway/README.md)
(guardrail de borda opcional), que não toca as aplicações.

## Veredito geral

O núcleo do padrão está correto e, em vários pontos, acima da média de mercado:
outbox transacional com claim/lease/fencing token, distinção poison vs.
transiente (incluindo indisponibilidade do Schema Registry), DLQ durável,
reserva de idempotência atômica com fingerprint, configuração validada no boot
e cobertura de testes de falha rara em POCs. Os problemas reais se concentram
em três eixos:

1. **Modelagem de identidade** — o conceito de *tenant* não existe no domínio
   (só no rate limiter), e é disso que derivam os dois achados críticos.
2. **Orçamentos de tempo não fechados** — o caminho da requisição tem um SLA de
   3s, mas contém operações com timeout default de minutos.
3. **Mecanismos de fundo dimensionados para o POC** — housekeeping, backoff,
   readiness e fan-out de pub/sub funcionam no alvo atual (1.000 req/min) e
   quebram uma ordem de grandeza acima.

## Críticos

### 1. `Idempotency-Key` sem escopo de tenant (replay e vazamento cross-tenant)

- Onde: `payment-api .../redis/RedisStatusStore.java:117` (chave Redis
  `idem:{key}` global), `payment-sbus .../V3__idempotency_record.sql:12`
  (`UNIQUE (idempotency_key)` global), `ApiPaymentService.java:100-106`
  (replay devolve o resultado completo do requestId original).
- Problema: o fingerprint usa só campos do corpo, todos controlados pelo
  cliente. Tenant B que repita a chave e o payload de A recebe o **resultado de
  A** (código de autorização, taxas). Com payload diferente, gera 409 alheio —
  negação de serviço por colisão de namespace.
- Por que importa: vazamento de dado transacional entre clientes é incidente
  reportável, não bug. A probabilidade de colisão cresce com o número de
  integradores.
- Recomendação: derivar `tenantId` da credencial autenticada (nunca do corpo);
  chave `idem:{tenantId}:{key}`; `tenantId` no fingerprint; constraint
  `UNIQUE (tenant_id, idempotency_key)`; propagar `tenantId` no envelope para o
  Sbus resolver replay no escopo certo.

### 2. `Idempotency-Key` opcional — ausência gera UUID aleatório

- Onde: `ApiPaymentService.java:74-76`; header com `defaultValue = ""` nos dois
  controllers.
- Problema: sem o header, cada tentativa é uma operação nova. É especialmente
  perigoso porque o Edge tem dois desfechos que um proxy/cliente naturalmente
  retenta (503 de publish/store e 202 de timeout). Toda a maquinaria de
  idempotência fica inerte quando o cliente omite o header.
- Recomendação: tornar a chave obrigatória (400 `problem+json` na ausência) —
  padrão em APIs de pagamento — ou derivá-la deterministicamente do fingerprint
  em janela. Nota: o `gateway` adicionado já respeita isso — POST nunca é
  re-tentado após poder ter alcançado o upstream.

## Altos

| # | Achado | Onde | Por que importa / recomendação |
|---|---|---|---|
| 3 | Chave de idempotência sem validação de tamanho/formato | controllers vs. `VARCHAR(128)` no Sbus | chave >128 chars passa pelo Edge, quebra constraint no Sbus, é classificada transiente, faz 5 retries e cai em DLQ — cliente vê 202 eterno; amplificador de recursos acionável externamente. Validar com `@Size`/`@Pattern` e classificar violação de constraint como poison |
| 4 | Publish Kafka sem orçamento na thread da requisição | `PaymentRequestProducer.java` (send bloqueante); producer sem `max.block.ms`/`delivery.timeout.ms` | SLA do Edge é 3s, mas o publish pode segurar a requisição por ~2min com broker degradado. O Sbus já resolve isso (`KafkaProducerFactory` deriva budgets); replicar no Edge e validar `publish budget < wait-timeout` no boot |
| 5 | Filtros HTTP fazem I/O Redis bloqueante no event loop Netty | `ConcurrencyLimitFilter.java:58-63` | filtro legado roda antes do `@ExecuteOn(BLOCKING)`; Redis lento (timeout 2s) congela os event loops inteiros, inclusive `/health` — latência vira kill do pod. Migrar para `@ServerFilter` com executor blocking ou Lettuce assíncrono |
| 6 | Housekeeping três ordens de grandeza abaixo da ingestão | `OutboxHousekeeping` (100 linhas/h), `RetentionHousekeeping` (500/h) vs. ~2,9M linhas/dia no alvo | crescimento ilimitado de `outbox_event`; iterar o DELETE em lotes até esgotar com teto de tempo, alarmar sobre tamanho de tabela, particionar por tempo a médio prazo |
| 7 | Backoff exponencial sem jitter | `BackoffCalculator.java:11-15` | após indisponibilidade, todas as linhas vencem no mesmo instante — retry storm sincronizado sobre a dependência que acabou de voltar. Jitter full/decorrelated; a classe é isolada e unit-testada, mudança contida |
| 8 | Readiness acoplada a dependência não crítica, sem opção de desacoplar | `DependencyPolicies.java:73-76` rejeita `readiness-required: false`; Redis do Sbus é só rate limit | blip de Redis derruba a readiness de toda a frota Sbus e com ela o endpoint interno que é o fallback durável do Edge — a degradação graciosa morre quando é mais necessária. Três níveis: liveness/readiness/degraded |
| 9 | Readiness de Postgres não observa exaustão do pool | `PostgresHealthIndicator` bypassa o pool (decisão boa) mas nada observa o Hikari (10 conexões, ~7 consumidores) | exaustão de pool é o modo de falha mais provável em pico e a readiness continua UP; adicionar indicador de pool + gauges/alerta do Hikari |
| 10 | `auto-register` de schema ligado em prod no Edge | `ContractCodecFactory.java:21` default true; `application-prod.yml` do Edge não desliga (o do Sbus desliga) | governança de contrato perdida silenciosamente: deploy com contrato divergente registra schema novo em vez de falhar. Desligar em prod e acrescentar ao `ProductionSecurityGuard` |
| 11 | Payload de pagamento em log ERROR (base64) | `RetryPublisher.java:90-95` | copia dado de transação para o stack de logs (retenção/acesso diferentes do banco); em incidente de Postgres o volume explode. Logar só ponteiro (`topic/partition/offset/key`); quarentena de bytes em tabela com o mesmo controle de acesso do domínio |
| 12 | Wake-up da correlação é at-most-once | `ResponseCoordinator` espera só o `future.get(3s)`; pub/sub Redis é fire-and-forget | durante failover de Redis, 100% das requisições em voo viram 202 mesmo com resultado pronto — queda de SLA + pico de polling durante o incidente. Alternar `future.get(250ms)` com releitura do Redis dentro do orçamento |

## Médios (resumo)

- **Trace distribuído quebra no último salto** — eventos finais publicados sem
  `traceparent` (`PaymentSimulationService` passa `null`) e o outbox publica
  via producer cru não instrumentado. Correlação por log sobrevive (traceId no
  MDC); a por trace, não. Injetar contexto W3C na publicação do outbox com
  span próprio e `Link` para o contexto de ingestão.
- **Janelas de idempotência divergentes** — 15min no Edge vs. 7d no Sbus:
  depois de 15min, mesma chave + payload diferente vira pagamento novo em vez
  de 409. Alinhar janelas ou consultar o Sbus na reserva; publicar o TTL no
  contrato.
- **Correlation-id do gateway não é herdado** — o Edge gera `correlationId`
  próprio e ignora `x-request-id`/`x-correlation-id` de entrada; logs do proxy
  e da aplicação não têm chave comum fora do trace. Aceitar com validação de
  formato e devolver no response.
- **Gauges com `COUNT(*)` por scrape** (`SbusMetrics`) — carga crescente no
  Postgres, no mesmo pool do fluxo; cachear com TTL ou manter contadores nas
  transições.
- **`max.poll.interval.ms` de 35min** — handler travado vivo prende a partição
  por meia hora sem rebalance; tirar retry longo do loop de consumo (a
  infraestrutura durável já existe para isso).
- **Outbox serial + 1 thread por listener + 3 partições** — teto de escala
  horizontal baixo; paralelizar sends do lote (mantendo `markPublished` por
  item) e dimensionar partições/threads ao pico projetado.
- **Fan-out do pub/sub num canal único** — todo evento final chega a todas as
  instâncias do Edge; shardar o canal por hash do requestId quando a frota
  crescer.
- **`OutboxPublicationLock` frágil** — conexão sem try-with-resources (só não
  vaza pelo escopo `@Connectable`) e advisory lock em namespace global do
  banco; explicitar o fechamento e usar a variante de dois argumentos com
  classid dedicado.
- **Pool de codecs Avro sem métrica e fail-closed para 503** — exaustão
  aparece para o cliente como falha de infra sem sinal operacional; exportar
  o `PoolSnapshot` que já existe.
- **DEBUG por default no logback das duas fronteiras produtivas**; nível por
  variável de ambiente com default INFO.
- **API key comparada sem tempo constante e em claro na config** — mitigada
  pelos guards de produção; migrar para comparação de hash (`MessageDigest.isEqual`)
  e armazenar hashes.
- **Campos de negócio sem `@Size`** (`merchantId`, `captureMode`) — mesma
  classe de problema do item 3.
- **Versionamento de API inconsistente** — `/payment-simulations` sem versão
  vs. `/v0/...`; `eventVersion` propagado mas nunca lido. Definir estratégia e
  aplicar uniformemente.
- **Dados de pagamento sem proteção em repouso no nível de aplicação** — hoje
  não há PAN/portador no payload (sem violação), mas não existe criptografia
  de campo/tokenização; definir a política agora e um teste de guarda que
  falhe se um campo sensível entrar em claro, antes que o modelo evolua.

## Baixos

`EVAL` em vez de `EVALSHA` no rate limiter; janela fixa admite 2x a rajada na
fronteira de janelas; ordenação do outbox não preservada sob retry (inócuo com
um evento terminal por requestId, deixa de ser se um agregado emitir mais);
ausência de contract test do HTTP interno Edge↔Sbus (duas classes acopladas
por convenção — Pact fecharia); reaper conta rolling deploy como tentativa de
falha (adicionar drain com `@PreDestroy` no dispatcher); endpoints de
management todos abertos no `payment-core-mock` (aceitável por ser
`NON_PRODUCTION`, não copiar o padrão).

Nota factual adicional: o `SbusStatusClient` do Edge não anexa `Authorization`
na chamada ao endpoint interno do Sbus (que exige `ROLE_PAYMENT_API`); a falha
degrada silenciosamente para "sem informação extra" via `Optional.empty()` do
`SbusStatusGateway`. Ou o fallback durável nunca funcionou de fato em ambiente
com segurança ligada, ou funciona apenas onde a role não é exigida — vale um
teste de integração que prove o caminho com autenticação real.

## O que está bem e deve ser preservado

- Outbox com `FOR UPDATE SKIP LOCKED`, fencing por claim token em todas as
  mutações, marcação por item e renovação de lease — nível produtivo.
- Reserva de idempotência atômica com estado de publicação (`PUBLISH_FAILED`
  retomado sob o mesmo requestId).
- Entrega duplicada tratada em todos os consumidores; terminal nunca reescrito.
- Poison vs. transiente com discriminação de registry indisponível.
- Rate limiter dual (rota+tenant) em um round-trip Lua com rollback correto;
  degradação fail-closed por fração de orçamento.
- Guards de startup (`ProductionSecurityGuard`, validações de TTL/retention).
- RFC 7807 consistente; tenant só como hash em chave/log/métrica.
- Cobertura de testes de falha (outage de Redis, replay de consumer group,
  DLQ recuperável, perfis de produção) muito acima do usual.

## Ordem de ataque sugerida

1. **Agora (baratos e estruturais):** tenant no domínio da idempotência (1),
   chave obrigatória + validada (2, 3), `auto-register` off em prod (10),
   jitter no backoff (7), log sem payload (11), DEBUG→INFO.
2. **Antes de qualquer aumento de alvo de carga:** budgets do producer do Edge
   (4), filtros fora do event loop (5), housekeeping proporcional (6),
   readiness em três níveis (8, 9), métricas dos recursos finitos (codec pool,
   Hikari, retry topics).
3. **Evolução:** re-poll no waiter (12), trace através do outbox, sharding do
   canal de correlação, paralelismo do outbox/consumers, estratégia de
   versionamento, criptografia de campo.

A camada [`gateway`](../gateway/README.md) cobre desde já, sem tocar código: a
rota `/v0` anônima passa a ter barreira de token na borda, retries de cliente
ficam disciplinados (POST nunca re-tentado após alcançar o upstream) e abuso
volumétrico é cortado antes do Edge.

## Status de remediação (2026-08)

Toda a remediação está registrada como a feature
[`review-2026-08-remediation`](../.specs/features/review-2026-08-remediation/)
(spec/design/tasks/STATE — `tasks.md` traz o commit exato de cada tarefa;
`spec.md` traz a tabela completa de rastreabilidade por requisito, todos
`Verified`). A tabela abaixo aponta, por achado desta revisão, a(s) tarefa(s)
que fecharam.

| Achado | Status | Tarefa(s) |
| --- | --- | --- |
| Crítico 1 — Idempotency-Key sem escopo de tenant | ✅ Resolvido | T1, T3, T4, T6, T7, T8, T9, T10, T11, T46 |
| Crítico 2 — Idempotency-Key opcional | ✅ Resolvido | T5, T9 |
| Alto 3 — chave de idempotência sem validação de tamanho/formato | ✅ Resolvido | T5 |
| Alto 4 — publish Kafka sem orçamento na thread da requisição | ✅ Resolvido | T16 |
| Alto 5 — filtros HTTP bloqueantes no event loop Netty | ✅ Resolvido | T17, T18 |
| Alto 6 — housekeeping três ordens de grandeza abaixo da ingestão | ✅ Resolvido | T28 |
| Alto 7 — backoff exponencial sem jitter | ✅ Resolvido | T27 |
| Alto 8 — readiness acoplada a dependência não crítica | ✅ Resolvido | T29 |
| Alto 9 — readiness de Postgres não observa exaustão do pool | ✅ Resolvido | T30 |
| Alto 10 — `auto-register` de schema ligado em prod no Edge | ✅ Resolvido | T20 |
| Alto 11 — payload de pagamento em log ERROR (base64) | ✅ Resolvido | T32 |
| Alto 12 — wake-up da correlação at-most-once | ✅ Resolvido | T22 |
| Médio — trace distribuído quebra no último salto | ✅ Resolvido | T34 |
| Médio — janelas de idempotência divergentes (15min vs 7d) | ✅ Resolvido | T6 |
| Médio — correlation-id do gateway não herdado | ✅ Resolvido | T23 |
| Médio — gauges com `COUNT(*)` por scrape | ✅ Resolvido | T35 |
| Médio — `max.poll.interval.ms` de 35min | ✅ Resolvido | T37 |
| Médio — outbox serial + 1 thread + 3 partições | ✅ Resolvido | T38, T39, T40 |
| Médio — fan-out do pub/sub num canal único | ✅ Resolvido | T24 |
| Médio — `OutboxPublicationLock` frágil | ✅ Resolvido | T41 |
| Médio — pool de codecs Avro sem métrica | ✅ Resolvido | T35, T36 |
| Médio — DEBUG por default no logback | ✅ Resolvido | T20, T32 |
| Médio — API key comparada sem tempo constante / em claro | ✅ Resolvido | T19 |
| Médio — campos de negócio sem `@Size` | ✅ Resolvido | T5 |
| Médio — versionamento de API inconsistente | ✅ Resolvido | T13, T26, T47, T48 |
| Médio — dados de pagamento sem proteção em repouso | ✅ Resolvido (política + teste de guarda; criptografia de campo é projeto próprio, ver Out of Scope do spec.md) | T2 |
| Baixo — `EVAL` em vez de `EVALSHA`; janela fixa admite 2× a rajada | ✅ Resolvido | T25, T33 |
| Baixo — ordenação do outbox não preservada sob retry | ⚪ Risco aceito conscientemente — inócuo enquanto um requestId emitir só um evento terminal; reavaliar se isso mudar | nenhuma (aceito) |
| Baixo — ausência de contract test HTTP interno Edge↔Sbus | ✅ Resolvido | T14, T15 |
| Baixo — reaper conta rolling deploy como tentativa de falha | ✅ Resolvido | T31 |
| Baixo — endpoints de management abertos no `payment-core-mock` | ⚪ Risco aceito conscientemente — fronteira é `NON_PRODUCTION` por decisão AD-005; padrão não deve ser copiado às fronteiras produtivas | nenhuma (aceito) |
| Nota factual — `SbusStatusClient` sem `Authorization` no fallback | ✅ Resolvido | T21 |

Achados adicionais fechados fora da revisão original (pedido explícito do
usuário, feature própria dentro do mesmo plano): paridade do gateway em
Kubernetes (K8S-01..05, T42-T46) e documentação/ADRs associados (T47, T48).

Todos os 6 boundaries do workspace (`payment-contracts`, `payment-api`,
`payment-sbus`, `payment-core-mock`, `feature-control`, `async-redis-service`)
tiveram seu `./gradlew test -PwithIT` completo (Testcontainers reais, não só
unitários) executado ao vivo nesta sessão pela primeira vez desde que T21
introduziu a deferral — ver `.specs/STATE.md` para o detalhe de cada bug real
encontrado e corrigido nesse processo (2 em `payment-sbus`, fora do escopo
desta revisão: `HikariPoolHealthIndicator` vazando conexão e derrubando
readiness, `SbusMetrics` derrubando o boot inteiro num outage do Postgres).
