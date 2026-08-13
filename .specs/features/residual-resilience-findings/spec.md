# Residual Resilience Findings Specification

## Problem Statement

A revisão pós-fechamento de `repository-segregation-production-hardening` levantou cinco achados profundos de correção. Quatro já foram corrigidos e commitados (`44c71de`, `e237f28`, `9ebf152`). Restam dois, ambos comprovados por execução ao vivo do gate `scripts/verify-workspace.sh` em 2026-08-13:

1. **`payment-api` não falha fechada quando o Redis cai.** `RedisStatusStore` não trata exceção alguma do Lettuce: a requisição vira `500` com o corpo `Internal Server Error: Unable to connect to redis/<unresolved>:6379` — vazando host e porta da infraestrutura. `design.md` §7.2 documenta o oposto ("produção falha fechada para idempotência/admissão"), e o cenário `redis-unavailable-api` já assere a garantia documentada, não o comportamento atual.

2. **O cenário `outbox-crash-window-reclaim` é não-determinístico.** Seu setup procura a linha de outbox terminal fixando `topic = 'payment.simulation.completed'`. Quando o `payment-core-mock` recusa a simulação — decisão probabilística, e foi o que ocorreu na execução ao vivo (`request_id=18c3528f`, `status=FAILED`, `error_code=51`) — o tópico terminal é `payment.simulation.failed` e o setup falha sem que exista defeito de produto. Verificado: ambas as linhas de outbox estavam `PUBLISHED`; o outbox funcionou corretamente.

O efeito combinado é que o gate de matriz de falhas reprova (`9/11`, `Done-when` exige `>=10`) por um defeito real e um defeito de teste, sem distinguir um do outro.

## Goals

- [ ] `payment-api` responde `503` (nunca `500`, nunca `404`) enquanto o Redis está indisponível, sem expor detalhe de infraestrutura no corpo.
- [ ] O cenário `outbox-crash-window-reclaim` produz o mesmo veredito independentemente da decisão aprovar/recusar do Core.
- [ ] `scripts/verify-workspace.sh payment-failures` atinge `11/11` numa execução ao vivo, com relatório datado.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Health/readiness indicator de Redis em `payment-api` | `design.md` §7.2 cita "health down", mas o boundary hoje não expõe indicador de dependência algum; criar um é mudança de contrato de observabilidade, não fechamento de achado. Vira tarefa própria. |
| Alterar o fallback degradado do `RedisRateLimiter` | Já corrigido em `bfd37cf` (divide o budget por instância). Ver Assumptions: admissão degradada e idempotência falham de formas deliberadamente diferentes. |
| Tornar a decisão do `payment-core-mock` determinística | Consertaria o sintoma no lugar errado: o valor do cenário está justamente em rodar contra um Core realista. O cenário é que deve tolerar os dois desfechos. |
| Os quatro achados já corrigidos | Fechados e verificados em `44c71de`, `e237f28`, `9ebf152`. |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Código de falha fechada é `503`, não `429` | `503 Service Unavailable` | `429` significa "você excedeu sua cota, tente de novo"; aqui a dependência caiu. `PublishFailedExceptionHandler` já usa `503` para o mesmo tipo de falha (Kafka fora), então `503` mantém o precedente do boundary. O cenário do gate aceita ambos. | y |
| `GET /payment-simulations/{id}` com Redis fora responde `503`, não `404` | `503` | Um `404` afirmaria que a requisição não existe — falso e irrecuperável para o cliente, que pode concluir que o pagamento nunca foi aceito. Falhar fechado exige admitir ignorância, não inventar ausência. | y |
| O `RedisRateLimiter` **mantém** o fallback local degradado; só o `RedisStatusStore` falha fechado | manter comportamento atual | `design.md` §7.2 diz "falha fechada para idempotência/admissão". Idempotência não tem substituto local — sem Redis não há como não duplicar, logo falha fechada. Admissão tem: o limiter já admite uma fatia local limitada (`limit / instances`), o que é bounded, não fail-open. Tornar a admissão fechada derrubaria a API inteira num soluço de Redis. | y — confirmado pelo usuário |
| O consumidor Kafka (`PaymentResponseConsumer`) não deve confirmar offset se a escrita do status falhar | **já correto — achado revisado na Fase 1** | Leitura de `PaymentResponseConsumer.java` (T5): `apply()` nunca engole exceção própria, `applyWithinBudget()` tenta `maxAttempts` vezes e então despacha para o DLQ via `ResponseDeadLetters.route()`, que por sua vez propaga se o próprio DLQ falhar. Com `OffsetStrategy.SYNC_PER_RECORD`, o offset só comita quando `receive()` retorna normalmente — ou seja, somente depois que o resultado pousou em algum lugar recuperável (Redis ou DLQ). Não havia bug; a RES-05 já valia antes desta feature. T5 vira tarefa de fechar a lacuna de cobertura (o cenário de falha no Redis especificamente nunca tinha teste, só o de decode) em vez de corrigir produção. | y |
| O cenário do gate passa a aceitar qualquer tópico terminal | consultar `topic IN ('payment.simulation.completed','payment.simulation.failed')` | É o conjunto fechado de tópicos terminais que o SBUS produz (`Topics.COMPLETED`, `Topics.FAILED`); qualquer um deles prova igualmente bem o reclaim do outbox. | y |

**Open questions:** uma — a linha marcada `n` acima (fallback degradado do limiter permanece como está). Registrada como decisão do usuário; a implementação segue o default escolhido salvo instrução em contrário.

---

## User Stories

### P1: Falha fechada sob indisponibilidade do Redis ⭐ MVP

**User Story**: Como integrador do `payment-api`, quero uma rejeição controlada quando o Redis cai, para distinguir "não consegui aceitar seu pagamento" de "seu pagamento quebrou o servidor" — e para não receber o endereço interno da infraestrutura numa resposta HTTP.

**Why P1**: É o único dos dois achados que é defeito de produto, está documentado como garantia em `design.md` §7.2, e hoje vaza `redis/<unresolved>:6379` para qualquer chamador anônimo que tente durante uma queda.

**Acceptance Criteria**:

1. IF o Redis está inalcançável WHEN um cliente faz `POST /payment-simulations` THEN o `payment-api` SHALL responder `503` com corpo `application/problem+json`. <!-- unwanted-behavior -->
2. WHILE o Redis está inalcançável, WHEN um cliente faz `GET /payment-simulations/{requestId}` e o fallback durável do SBUS responde, o `payment-api` SHALL responder `200` com o status durável. <!-- complex -->
3. IF o Redis está inalcançável **e** o fallback durável do SBUS também não responde WHEN um cliente faz `GET /payment-simulations/{requestId}` THEN o `payment-api` SHALL responder `503`, e SHALL NOT responder `404`. <!-- unwanted-behavior -->
4. O corpo de qualquer resposta de erro do `payment-api` SHALL NOT conter host, porta, URI de conexão ou texto de exceção de driver de infraestrutura. <!-- ubiquitous -->
5. IF a escrita do status no Redis falha WHEN o `PaymentResponseConsumer` processa um registro de resposta THEN o consumidor SHALL propagar a falha em vez de confirmar o offset. <!-- unwanted-behavior -->
6. WHEN o Redis volta a ficar alcançável THEN o `payment-api` SHALL voltar a aceitar requisições sem reinício do processo. <!-- event-driven -->

**Independent Test**: parar o container de Redis, fazer `POST` e `GET`, observar `503` em ambos e nenhum token de infraestrutura no corpo; religar o Redis e observar `200/202` sem reiniciar a aplicação.

---

### P2: Veredito determinístico da matriz de falhas

**User Story**: Como responsável pelo gate de release, quero que `outbox-crash-window-reclaim` reprove somente diante de defeito real, para que `9/11` signifique nove garantias comprovadas e não oito mais um sorteio.

**Why P2**: Não é defeito de produto, mas um gate que reprova aleatoriamente é um gate que se aprende a ignorar — e este já está mascarando a contagem `Done-when >= 10`.

**Acceptance Criteria**:

1. WHEN o cenário roda e a simulação atinge qualquer estado terminal THEN o setup SHALL localizar a linha de outbox terminal correspondente ao tópico efetivamente produzido. <!-- event-driven -->
2. IF nenhuma linha de outbox terminal existe para a requisição THEN o cenário SHALL reprovar identificando qual estado terminal a requisição atingiu. <!-- unwanted-behavior -->
3. O cenário SHALL NOT depender da decisão aprovar/recusar do `payment-core-mock` para determinar seu veredito. <!-- ubiquitous -->

**Independent Test**: rodar o cenário repetidamente até que o Core produza cada um dos dois desfechos, e observar o mesmo veredito nos dois casos.

---

### P3: Evidência datada do gate completo

**User Story**: Como owner do boundary, quero uma execução ao vivo registrada da matriz completa, para que o fechamento destes achados tenha a mesma qualidade de prova exigida em AD-005.

**Why P3**: AD-005 exige relatório datado para qualquer claim de prontidão; sem isso o fechamento é auto-declarado.

**Acceptance Criteria**:

1. WHEN a matriz de falhas de pagamento roda ao vivo após as correções THEN o resultado SHALL ser `11/11` e SHALL ser registrado com data no relatório do boundary. <!-- event-driven -->

---

## Edge Cases

- IF o Redis cai entre a reserva de idempotência e o publish no Kafka THEN o `payment-api` SHALL responder `503` mantendo a reserva recuperável (comportamento `PUBLISH_FAILED` já existente).
- IF o Redis volta durante o atendimento de uma requisição THEN o `payment-api` SHALL reconectar de forma transparente (`RedisStatusStore.commands()` já reconecta preguiçosamente).
- WHEN o Redis está fora e o `RedisRateLimiter` degrada localmente THEN a admissão SHALL continuar limitada pelo budget por instância, sem virar fail-open.
- IF o Core recusa a simulação no cenário de outbox THEN o cenário SHALL usar a linha de tópico `payment.simulation.failed` com o mesmo rigor da `completed`.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| RES-01 | P1: Falha fechada (POST → 503) | Tasks | In Tasks |
| RES-02 | P1: GET usa o fallback durável do SBUS em vez de falhar | Tasks | Pending |
| RES-03 | P1: GET → 503 quando nenhum store responde (nunca 404) | Tasks | Pending |
| RES-04 | P1: Nenhum detalhe de infraestrutura no corpo | Tasks | Pending |
| RES-05 | P1: Consumer não confirma offset em falha de escrita | Tasks | Pending |
| RES-06 | P1: Recuperação sem reinício | Tasks | Pending |
| RES-07 | P2: Setup do cenário aceita qualquer tópico terminal | Tasks | Pending |
| RES-08 | P2: Falha do cenário identifica o estado terminal atingido | Tasks | Pending |
| RES-09 | P3: Execução ao vivo 11/11 com evidência datada | Tasks | Pending |

**ID format:** `RES-[NUMBER]`

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 9 total, 9 mapeados para tarefas, 0 não mapeados

---

## Success Criteria

- [ ] `POST` e `GET` respondem `503` com Redis fora; nenhum corpo contém `redis`, host ou porta.
- [ ] `scripts/verify-workspace.sh payment-failures` atinge `11/11` ao vivo.
- [ ] O cenário `outbox-crash-window-reclaim` produz o mesmo veredito sob desfecho aprovado e recusado do Core.
- [ ] Nenhuma regressão nas 121 asserções da suíte do `payment-api`.
