# Review 2026-08 Remediation Specification

## Problem Statement

A revisão de arquitetura 2026-08 (`docs/architecture-review-2026-08.md`)
encontrou 2 achados críticos (idempotência sem escopo de tenant com vazamento
cross-tenant; chave de idempotência opcional), 10 altos, 14 médios e um
conjunto de baixos que separam o POC de um deploy bancário real. Além disso, a
fronteira `gateway` só existe para compose; falta o caminho Kubernetes com
Envoy Gateway real (Gateway API) mantendo paridade semântica com o sandbox.

## Goals

- [ ] Zerar os achados da revisão 2026-08: nenhum item crítico/alto/médio/baixo
      permanece sem correção implementada e testada.
- [ ] Tenant como conceito de domínio: isolamento de idempotência, status e
      replay provado por testes de integração cross-tenant.
- [ ] Caminho Kubernetes do gateway: manifests Gateway API validados no CI e
      com paridade semântica verificada contra o compose.
- [ ] Gates existentes preservados: builds das fronteiras, verify-workspace e
      alvo de capacidade AD-007 continuam passando após as mudanças.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Políticas OPA/ext_authz reais no gateway | Extension point documentado; manter guardrail simples (decisão do usuário na feature do gateway) |
| Criptografia de envelope real por campo | Esta feature entrega a política + teste de guarda de campos sensíveis; a criptografia é projeto próprio |
| TLS interno (gateway→Edge, Kafka SASL/SSL, Redis/Postgres) | Pendência já registrada em `docs/production-evidence.md`; infra de certificados internos é feature própria |
| Divisor de degradação ciente de autoscaling | Requer integração com orquestrador; `PAYMENT_API_INSTANCES` manual documentado permanece |
| Cluster Kubernetes no CI | Validação client-side (kubeconform + paridade); subir kind/k3s no CI é custo sem retorno proporcional |
| Repartitionamento de tópicos em ambientes vivos | Sandbox recria tópicos; runbook de produção citado em docs, não automatizado |
| Tornar o gateway obrigatório | Decisão de arquitetura vigente: opcional por contrato |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Janela unificada de idempotência publicada no contrato | 24h no Edge (Redis); retenção durável do Sbus permanece 7d | Cobre retries de fim de dia sem custo de memória proibitivo; 15min atual é curto demais e 7d em Redis é caro | n (design pode ajustar valor, contrato documenta o escolhido) |
| Formato do binding api-key→tenants | Mapa em configuração tipada do Edge (`payment.security.tenants`), chave = hash SHA-256 da API key | Segue o padrão existente de identificar tenant por hash, nunca pela credencial em claro | y (decisão de confiança do usuário) |
| Estratégia de versionamento de API | Path só para major (`/v0` beta permanece), header `X-Api-Version` na resposta, ADR registra a regra; `eventVersion` passa a ser validado nos consumers | Menor superfície de mudança compatível com o que já existe (`/v0`, `X-Api-Version` já emitido) | n |
| Shards do canal de correlação | Configurável, default 4 canais (`payment-sim-responses-{0..N-1}` por hash do requestId) | Remove o teto de fan-out sem mudar semântica; N=4 é inócuo em sandbox e prova o mecanismo | n |
| Concorrência de consumers / partições | `threads` configurável por listener (default 3) e partições do sandbox parametrizadas (default 6) | Dobra a folga sobre o alvo AD-007 sem repartitionar depois; produção dimensiona via env | n |
| Comparação e armazenamento de API keys | Comparação constant-time sobre hashes SHA-256; configuração passa a aceitar hashes (mantendo aceitação de valor em claro só em `dev`) | Fecha timing attack e remove credencial em claro de config de produção sem quebrar o fluxo dev | n |
| Housekeeping | Laço de lotes até esgotar com teto de tempo de 30s por execução e intervalo reduzido para 5min | Proporcional à ingestão do alvo AD-007 com margem de 3 ordens de grandeza | n |
| Correlation-id de entrada | Aceitar `x-correlation-id` (formato UUID ou [A-Za-z0-9-]{8,64}); inválido → ignorado e gerado novo (nunca 4xx) | Herança de correlação não pode virar vetor de rejeição nem de log injection | n |
| Sensibilidade de campos (teste de guarda) | Denylist inicial: `pan`, `card`, `cvv`, `cvc`, `password`, `secret`, `token` em payloads persistidos/logados | Ponto de partida da política; evolui com o modelo | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Isolamento de tenant e idempotência obrigatória ⭐ MVP

**User Story**: Como operador de uma plataforma multi-tenant de pagamentos,
quero que idempotência, status e replay sejam escopados pelo tenant autenticado
para que nenhum cliente consiga ler resultado, causar 409 ou deduplicar
operação de outro cliente.

**Why P1**: Achados críticos #1 e #2 — vazamento de dado transacional
cross-tenant é incidente reportável; chave opcional torna toda a maquinaria de
idempotência inerte.

**Acceptance Criteria**:

1. WHEN `POST /payment-simulations` chega sem header `Idempotency-Key` THEN o Edge SHALL responder `400 application/problem+json` sem publicar evento nem reservar estado. <!-- IDEM-01 -->
2. IF o header `Idempotency-Key` exceder 128 caracteres ou violar o padrão `[A-Za-z0-9_-]+` THEN o Edge SHALL responder `400 application/problem+json` antes de qualquer I/O de domínio. <!-- IDEM-02 -->
3. WHEN uma requisição autenticada chega com `X-Tenant-Id` fora do binding da sua API key THEN o Edge SHALL responder `403 application/problem+json`. <!-- TEN-01 -->
4. WHEN `X-Tenant-Id` está ausente e o binding da API key tem exatamente um tenant THEN o Edge SHALL usar esse tenant como tenant efetivo da requisição. <!-- TEN-02 -->
5. IF `X-Tenant-Id` está ausente e o binding da API key tem mais de um tenant THEN o Edge SHALL responder `400 application/problem+json` indicando o header obrigatório. <!-- TEN-03 -->
6. WHEN o tenant B reutiliza a `Idempotency-Key` e o payload exatos de uma operação do tenant A THEN o Edge SHALL tratar como operação nova do tenant B, sem devolver resultado, requestId ou 409 derivados do tenant A. <!-- TEN-04 -->
7. The Edge SHALL compor a reserva de idempotência, o fingerprint e as chaves de status com o tenant efetivo, e o envelope de eventos SHALL carregar o `tenantId` até o Sbus. <!-- TEN-05 -->
8. The Sbus SHALL garantir unicidade de idempotência por `(tenant_id, idempotency_key)` via migration, nunca por chave global. <!-- TEN-06 -->
9. WHILE a janela de idempotência publicada no contrato estiver vigente, WHEN a mesma chave chegar com payload diferente do mesmo tenant o Edge SHALL responder `409`, inclusive após o TTL curto anterior (janela do Edge alinhada ao contrato). <!-- IDEM-03 -->
10. IF a persistência no Sbus falhar por violação de constraint de dados (tamanho/formato) THEN o Sbus SHALL classificar a mensagem como poison (DLQ direta), nunca como transiente com retries. <!-- IDEM-04 -->
11. WHERE o gateway está na frente, o Envoy SHALL injetar `X-Tenant-Id` a partir de claim do JWT validado, e o Edge SHALL revalidar o header contra o binding exatamente como sem gateway. <!-- TEN-07 -->

**Independent Test**: IT cross-tenant com duas API keys/bindings distintos:
mesma chave+payload → dois requestIds independentes; chave repetida
payload-diferente → 409 só no tenant dono; sem header de idempotência → 400;
`X-Tenant-Id` forjado → 403.

---

### P1: Orçamentos de tempo fechados no caminho da requisição ⭐ MVP

**User Story**: Como operador do Edge, quero que nenhuma operação no caminho
da requisição possa exceder o orçamento declarado de resposta para que broker
ou Redis degradados produzam respostas bem formadas, nunca conexões presas.

**Why P1**: Achados altos #4 e #5 — publish sem orçamento segura a requisição
por minutos; I/O bloqueante no event loop transforma latência de Redis em
indisponibilidade total (inclusive `/health`).

**Acceptance Criteria**:

1. The Edge producer Kafka SHALL operar com `max.block.ms`, `request.timeout.ms` e `delivery.timeout.ms` derivados de um orçamento de publicação configurado, validado no boot como estritamente menor que `payment.simulation.wait-timeout`. <!-- BUDG-01 -->
2. IF o broker Kafka estiver indisponível no publish THEN o Edge SHALL responder `503 application/problem+json` dentro de `wait-timeout + 1s` no pior caso, nunca aguardar defaults de 60s. <!-- BUDG-02 -->
3. The Edge SHALL executar a admissão (API key + rate limit) fora das threads de event loop do Netty, em filtro com executor blocking ou via cliente Redis assíncrono. <!-- BUDG-03 -->
4. WHILE o Redis de admissão responde com latência ≥ 2s, o endpoint `/health/liveness` SHALL continuar respondendo `200` em menos de 500ms. <!-- BUDG-04 -->

**Independent Test**: IT com broker parado prova 503 dentro do orçamento; IT
com Redis latente (toxiproxy/latência injetada) prova liveness responsivo e
429/degradação em vez de congelamento.

---

### P1: Segurança e governança de produção ⭐ MVP

**User Story**: Como responsável por segurança, quero eliminar vazamento de
payload em logs, governança de schema furada, credenciais comparáveis por
timing e o fallback interno não autenticado, para que o perfil de produção
seja defensável em auditoria.

**Why P1**: Achados altos #10 e #11, médios de segurança e a lacuna factual do
`SbusStatusClient` sem credencial (fallback durável possivelmente nunca
funcionou com segurança ligada).

**Acceptance Criteria**:

1. WHERE o perfil `prod` está ativo no Edge, a configuração SHALL manter `payments.avro.auto-register=false` e o `ProductionSecurityGuard` SHALL derrubar o boot se estiver `true`. <!-- SEC-01 -->
2. The Sbus SHALL registrar mensagens em risco apenas com ponteiro recuperável (`topic/partition/offset/key`), nunca com payload em claro ou base64 em log. <!-- SEC-02 -->
3. The Sbus SHALL sanitizar mensagens de exceção antes de persisti-las como header Kafka ou coluna (`x-retry-reason`, `last_error`), truncadas e sem conteúdo de payload. <!-- SEC-03 -->
4. The Edge SHALL comparar API keys em tempo constante (`MessageDigest.isEqual`) sobre hashes, e o perfil `prod` SHALL aceitar apenas hashes na configuração de keys. <!-- SEC-04 -->
5. WHEN o Edge chama `GET /internal/payment-simulations/{id}` no Sbus THEN a chamada SHALL apresentar credencial de serviço com `ROLE_PAYMENT_API`, e um teste de integração com segurança ligada SHALL provar o caminho 200. <!-- SEC-05 -->
6. The logging default das fronteiras produtivas SHALL ser `INFO`, com nível ajustável por variável de ambiente sem rebuild. <!-- SEC-06 -->
7. The workspace SHALL ter um teste de guarda que falha quando um campo da denylist de sensibilidade aparece em payload persistido ou logado em claro, com a política registrada em ADR. <!-- SEC-07 -->
8. The DTOs de entrada do Edge SHALL impor limites de tamanho e formato em todos os campos string (`merchantId`, `captureMode` incluídos), rejeitando violações com `400`. <!-- SEC-08 -->

**Independent Test**: boot de perfil prod com auto-register/keys em claro
falha; IT do fallback com JWT de serviço; teste de guarda vermelho ao inserir
campo `cardNumber` em payload persistido; grep de logs de um fluxo com falha
forçada não contém base64 do payload.

---

### P1: Resiliência de fundo em escala ⭐ MVP

**User Story**: Como operador do Sbus, quero que os mecanismos de fundo
(housekeeping, retries, readiness, shutdown) sustentem o alvo de carga sem
crescimento ilimitado, retry storm ou perda de degradação graciosa.

**Why P1**: Achados altos #6, #7, #8, #9 — são os que transformam incidente
curto em incidente longo, e só aparecem acima do alvo atual.

**Acceptance Criteria**:

1. The `BackoffCalculator` SHALL aplicar jitter de no mínimo ±20% ao atraso exponencial, com testes provando dispersão de instantes de vencimento para lotes que falharam juntos. <!-- RES-01 -->
2. WHEN o housekeeping executa THEN ele SHALL iterar deleções em lotes até esgotar o elegível ou atingir um teto de tempo configurável, e SHALL expor métrica do total purgado e do restante. <!-- RES-02 -->
3. WHILE o Redis do Sbus (rate limiter do Core) está indisponível, a readiness do Sbus SHALL permanecer `UP` com o componente reportado como degradado, e `/internal/payment-simulations/{id}` SHALL continuar respondendo. <!-- RES-03 -->
4. The configuração de dependências do Sbus SHALL permitir `readiness-required: false` por dependência, removendo a validação que o proibia. <!-- RES-04 -->
5. The Sbus SHALL expor um health indicator do pool Hikari (aquisição com timeout curto) e gauges de conexões ativas/pendentes/timeouts com alerta correspondente. <!-- RES-05 -->
6. WHEN o Sbus recebe shutdown ordenado THEN o dispatcher do outbox SHALL parar de reivindicar lotes novos e liberar os claims não publicados sem incrementar `attempts`. <!-- RES-06 -->

**Independent Test**: teste de dispersão do jitter; IT de housekeeping com
backlog sintético maior que um lote; IT de readiness com Redis derrubado; IT
de shutdown provando claims liberados com `attempts` inalterado.

---

### P2: Correlação e observabilidade ponta a ponta

**User Story**: Como engenheiro de operação, quero correlação resiliente e
trace contínuo do gateway ao evento final para diagnosticar incidentes sem
reconstruir o fluxo manualmente.

**Why P2**: Achado alto #12 (wake-up at-most-once) e médios de
observabilidade — degradam SLA percebido e diagnóstico, não correção.

**Acceptance Criteria**:

1. WHILE um waiter aguarda dentro do orçamento, IF a notificação pub/sub for perdida THEN o Edge SHALL detectar o resultado terminal por releitura periódica (intervalo ≤ 500ms) e responder `200/422` dentro do `wait-timeout`. <!-- OBS-01 -->
2. The Sbus SHALL propagar contexto W3C (`traceparent`) nos eventos finais e a publicação do outbox SHALL gerar span próprio com link para o contexto de ingestão persistido. <!-- OBS-02 -->
3. WHEN uma requisição chega com `x-correlation-id` válido THEN o Edge SHALL adotá-lo como correlationId (propagado a logs, eventos e resposta); IF inválido THEN o Edge SHALL ignorá-lo e gerar um novo, nunca rejeitar a requisição. <!-- OBS-03 -->
4. The gauges de contagem do Sbus SHALL ler valores cacheados com TTL ≤ 30s, nunca executar `COUNT(*)` por scrape. <!-- OBS-04 -->
5. The pool de codecs Avro SHALL exportar gauges de capacidade/disponíveis/tomados/timeouts nas fronteiras que o usam. <!-- OBS-05 -->

**Independent Test**: IT que suprime o PUBLISH e prova 200 dentro do orçamento;
trace de um fluxo completo em Jaeger com spans encadeados por link; scrape em
loop não gera queries de contagem por scrape (verificável por métrica do
Postgres ou log de SQL).

---

### P2: Escala do processamento durável

**User Story**: Como arquiteto, quero remover os tetos estruturais de
throughput do Sbus e do canal de correlação para que escalar seja adicionar
instância/partição, não reescrever mecanismo.

**Why P2**: Médios de escala — corretos no alvo atual, limitantes acima dele.

**Acceptance Criteria**:

1. The consumers do Sbus SHALL operar com `max.poll.interval.ms` ≤ 5 minutos, movendo esperas longas para a infraestrutura durável de retry (nunca sleep no loop de consumo). <!-- SCAL-01 -->
2. The dispatcher do outbox SHALL publicar os eventos de um lote com sends paralelos, preservando `markPublished` por item e a semântica de lease/claim existente. <!-- SCAL-02 -->
3. The listeners Kafka das fronteiras SHALL ter concorrência configurável (`threads`) e o sandbox SHALL criar tópicos com número de partições parametrizado. <!-- SCAL-03 -->
4. The `OutboxPublicationLock` SHALL fechar a conexão via try-with-resources, usar `pg_try_advisory_lock(classid, objid)` com classid dedicado, e um teste SHALL provar não-crescimento de conexões ativas após N publicações. <!-- SCAL-04 -->
5. The canal de correlação Redis SHALL suportar sharding por hash do requestId com número de canais configurável, e cada instância SHALL receber o wake-up correto com N > 1. <!-- SCAL-05 -->
6. The rate limiters Redis SHALL usar `EVALSHA` com fallback para `EVAL`, e a janela de admissão SHALL usar aproximação de janela deslizante que impeça admitir 2× o orçamento na fronteira entre janelas. <!-- SCAL-06 -->

**Independent Test**: IT de outbox com lote ≥ 10 provando paralelismo sem
duplicação; IT de sharding com 2 shards e waiters nos dois; teste do limiter
provando rejeição de burst 2× na fronteira de janela.

---

### P2: Envoy Gateway em Kubernetes com paridade sandbox

**User Story**: Como plataforma, quero o mesmo guardrail de borda expresso em
Gateway API para clusters Kubernetes, mantendo o compose como caminho sandbox,
para que a semântica da borda seja uma só em qualquer ambiente.

**Why P2**: Pedido explícito do usuário (projeto pode usar Kubernetes; às
vezes só sandbox sem K8s).

**Acceptance Criteria**:

1. The fronteira gateway SHALL conter manifests Gateway API do Envoy Gateway (Gateway, HTTPRoute, SecurityPolicy com JWT do Keycloak, BackendTrafficPolicy com rate limit e circuit breaking, telemetria) sob `gateway/k8s/` com kustomize base + overlays `sandbox` e `prod-example`. <!-- K8S-01 -->
2. The manifests SHALL expressar a mesma semântica do compose: allowlist de rotas idêntica, JWT validado e não encaminhado, retry de POST restrito a falhas pré-upstream, timeouts maiores que o `wait-timeout` do Edge. <!-- K8S-02 -->
3. The CI SHALL validar os manifests com kubeconform (schemas Gateway API + CRDs do Envoy Gateway) e um script SHALL falhar quando a allowlist de rotas ou os limites divergirem entre `envoy.yaml` e os HTTPRoutes. <!-- K8S-03 -->
4. WHERE o gateway compose está na frente, o Envoy SHALL injetar `X-Tenant-Id` do claim JWT via configuração (sem código novo), e os manifests K8s SHALL declarar o equivalente. <!-- K8S-04 -->
5. The documentação do gateway SHALL orientar quando usar compose (sandbox local) e quando usar os manifests (cluster), incluindo instruções de aplicação com kind como exemplo. <!-- K8S-05 -->

**Independent Test**: kubeconform verde no CI; script de paridade falha ao
remover uma rota de um dos lados; smoke compose continua passando com o header
de tenant injetado.

---

### P3: Contrato e versionamento

**User Story**: Como integrador, quero regras de versionamento explícitas e
contratos internos testados para evoluir sem quebras silenciosas.

**Why P3**: Médio/baixos de contrato — importantes, sem risco imediato.

**Acceptance Criteria**:

1. The workspace SHALL registrar em ADR a estratégia de versionamento de API (path para major, headers de resposta) e aplicá-la de forma consistente nas rotas e documentação existentes. <!-- API-01 -->
2. WHEN um consumer Kafka recebe evento com `eventVersion` de major desconhecido THEN ele SHALL encaminhar para DLQ como poison com razão explícita, nunca processar silenciosamente. <!-- API-02 -->
3. The contrato HTTP interno Edge↔Sbus (`GET /internal/payment-simulations/{id}`) SHALL ter teste de contrato consumer-driven que falhe quando os campos JSON divergirem entre `SbusStatusResponse` e `SbusStatusView`. <!-- API-03 -->

**Independent Test**: teste de contrato vermelho ao renomear um campo de um
lado; IT de evento com `eventVersion: 99.0` indo a DLQ.

---

## Edge Cases

- IF a mesma `Idempotency-Key` chegar simultaneamente de dois tenants distintos THEN o sistema SHALL processar as duas como operações independentes sem interferência (chaves compostas por tenant).
- IF o binding de tenants da API key estiver vazio ou malformado THEN o Edge SHALL falhar o boot (guard), nunca aceitar requisições sem escopo.
- WHEN o housekeeping atinge o teto de tempo com backlog restante THEN o sistema SHALL registrar métrica/log do restante e continuar no próximo ciclo, sem lock prolongado.
- IF o script `EVALSHA` não estiver carregado (NOSCRIPT) THEN o limiter SHALL recarregar via `EVAL` na mesma requisição, sem negar admissão por isso.
- WHEN o número de shards do canal de correlação for alterado entre deploys THEN instâncias antigas e novas SHALL continuar acordando waiters (assinatura de todos os shards durante transição documentada).
- IF os manifests K8s referenciarem CRDs ausentes no cluster THEN a documentação SHALL apontar o pré-requisito (instalação do Envoy Gateway) e o kubeconform SHALL validar com os schemas corretos no CI.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| IDEM-01 | P1: Tenant e idempotência | Design | Pending |
| IDEM-02 | P1: Tenant e idempotência | Design | Pending |
| IDEM-03 | P1: Tenant e idempotência | Design | Pending |
| IDEM-04 | P1: Tenant e idempotência | Design | Pending |
| TEN-01 | P1: Tenant e idempotência | Design | Pending |
| TEN-02 | P1: Tenant e idempotência | Design | Pending |
| TEN-03 | P1: Tenant e idempotência | Design | Pending |
| TEN-04 | P1: Tenant e idempotência | Design | Pending |
| TEN-05 | P1: Tenant e idempotência | Design | Implementing |
| TEN-06 | P1: Tenant e idempotência | Design | Pending |
| TEN-07 | P1: Tenant e idempotência | Design | Pending |
| BUDG-01 | P1: Orçamentos de tempo | Design | Pending |
| BUDG-02 | P1: Orçamentos de tempo | Design | Pending |
| BUDG-03 | P1: Orçamentos de tempo | Design | Pending |
| BUDG-04 | P1: Orçamentos de tempo | Design | Pending |
| SEC-01 | P1: Segurança e governança | Design | Pending |
| SEC-02 | P1: Segurança e governança | Design | Pending |
| SEC-03 | P1: Segurança e governança | Design | Pending |
| SEC-04 | P1: Segurança e governança | Design | Pending |
| SEC-05 | P1: Segurança e governança | Design | Pending |
| SEC-06 | P1: Segurança e governança | Design | Pending |
| SEC-07 | P1: Segurança e governança | Design | Pending |
| SEC-08 | P1: Segurança e governança | Design | Pending |
| RES-01 | P1: Resiliência de fundo | Design | Pending |
| RES-02 | P1: Resiliência de fundo | Design | Pending |
| RES-03 | P1: Resiliência de fundo | Design | Pending |
| RES-04 | P1: Resiliência de fundo | Design | Pending |
| RES-05 | P1: Resiliência de fundo | Design | Pending |
| RES-06 | P1: Resiliência de fundo | Design | Pending |
| OBS-01 | P2: Correlação e observabilidade | Design | Pending |
| OBS-02 | P2: Correlação e observabilidade | Design | Pending |
| OBS-03 | P2: Correlação e observabilidade | Design | Pending |
| OBS-04 | P2: Correlação e observabilidade | Design | Pending |
| OBS-05 | P2: Correlação e observabilidade | Design | Pending |
| SCAL-01 | P2: Escala do processamento durável | Design | Pending |
| SCAL-02 | P2: Escala do processamento durável | Design | Pending |
| SCAL-03 | P2: Escala do processamento durável | Design | Pending |
| SCAL-04 | P2: Escala do processamento durável | Design | Pending |
| SCAL-05 | P2: Escala do processamento durável | Design | Pending |
| SCAL-06 | P2: Escala do processamento durável | Design | Pending |
| K8S-01 | P2: Envoy Gateway em Kubernetes | Design | Pending |
| K8S-02 | P2: Envoy Gateway em Kubernetes | Design | Pending |
| K8S-03 | P2: Envoy Gateway em Kubernetes | Design | Pending |
| K8S-04 | P2: Envoy Gateway em Kubernetes | Design | Pending |
| K8S-05 | P2: Envoy Gateway em Kubernetes | Design | Pending |
| API-01 | P3: Contrato e versionamento | Design | Pending |
| API-02 | P3: Contrato e versionamento | Design | Implementing |
| API-03 | P3: Contrato e versionamento | Design | Pending |

**ID format:** `[CATEGORY]-[NUMBER]`

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 47 total, 0 mapped to tasks, 47 unmapped ⚠️ (pré-design)

---

## Success Criteria

- [ ] Todos os 47 requisitos com status Verified na tabela de traceability.
- [ ] IT cross-tenant prova isolamento: 0 vazamentos de resultado/409 entre tenants nos cenários da story P1.
- [ ] Com broker parado, `POST` responde 503 em < `wait-timeout + 1s` (medido em IT).
- [ ] Com Redis latente ≥ 2s, `/health/liveness` responde em < 500ms (medido em IT).
- [ ] kubeconform e script de paridade compose↔K8s verdes no CI.
- [ ] Gates preexistentes verdes: builds das fronteiras, `scripts/verify-workspace.sh` 8/8, docs/governança/equivalência.
- [ ] Gate de capacidade AD-007 re-executado após as mudanças com veredito PASS (1.000 req/min sustentado, avg ≤ 300ms, p99 ≤ 10s, 429 ≤ 1% no steady).
