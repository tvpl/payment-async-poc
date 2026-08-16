# Adversarial Audit Fixes — Design

Só as correções com decisão de desenho real. As demais são reparos localizados cuja abordagem está no próprio achado (tasks.md referencia o `file:line`).

## 1. Kill-switch que sobrevive à queda do Redis (AUD-02)

**Problema:** a polaridade segura do mecanismo de stale é *off*; a do kill-switch é *on*. Hoje ambos os ramos de `StalePolicy` resolvem "não matado" quando o Redis some — o interruptor de emergência desarma exatamente quando mais importa.

**Decisão:** latch em memória no `MasterSwitch`. Toda leitura *bem-sucedida* do flag `__kill_switch__` atualiza o latch (`AtomicBoolean lastKnownKilled`). Quando a leitura falha ou resolve por política de stale, o `MasterSwitch` responde o **latch**, não a política. Transições só acontecem com leitura fresca confirmada.

**Cold start com Redis fora:** latch nasce desarmado. Sem storage local não há como saber o estado dinâmico; documentar que `master-enabled: false` (estático, YAML) é o break-glass que sobrevive a restart. Alternativa rejeitada: *fail-killed no boot sem Redis* — transformaria qualquer boot durante blip de Redis em outage total auto-infligido de todas as flags.

**Detecção de "leitura falhou" vs "flag não existe":** `RedisFlagSource` hoje colapsa os dois em `Optional.empty()`. O latch precisa distinguir. Expor no source um resultado trinário (`FOUND / ABSENT / UNAVAILABLE`) **internamente** (novo método, sem quebrar a API pública certificada — `FlagSource` público permanece; a interface interna é implementada por `RedisFlagSource` e consultada só pelo `MasterSwitch` via composite). `ABSENT` com Redis saudável = kill legitimamente removido → desarma. `UNAVAILABLE` → mantém latch.

## 2. Backoff de falha no RedisFlagSource (AUD-14)

**Problema:** sem caching negativo, cada avaliação durante uma queda entra no `synchronized` e paga um timeout de Lettuce em série — a app inteira serializa num monitor.

**Decisão:** timestamp de última falha por chave (`failureBackoffUntil` no cache entry). Dentro da janela (default `1s`, jitterado), `find()` responde a política de stale **sem lock e sem Redis**. A janela é curta de propósito: recuperação em ≤1s quando o Redis volta, mas nunca mais de ~1 comando/s/chave durante a queda.

## 3. Admissão atômica de dois orçamentos (AUD-05)

**Problema:** `!resource.tryAcquire() || !tenant.tryAcquire()` consome o token de rota mesmo quando o tenant nega (short-circuit não compensa), e um tenant estourado esgota a rota para todos.

**Decisão:** um único script Lua avaliando os dois contadores com rollback:

```lua
local r = redis.call('INCR', KEYS[1])
if r == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
if r > tonumber(ARGV[2]) then return 0 end          -- rota negou; INCR de rota é inócuo acima do limite
local t = redis.call('INCR', KEYS[2])
if t == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[1]) end
if t > tonumber(ARGV[3]) then
  redis.call('DECR', KEYS[1])                        -- devolve o token de rota
  return 0
end
return 1
```

Fallback degradado (Redis fora) mantém a fatia local por instância já existente, aplicada aos dois orçamentos. `/v0`: o filtro usa tenant `"anonymous"` fixo quando o path é a rota anônima — `X-API-Key` não validada nunca vira chave de bucket (AUD-05/v0 bypass).

## 4. Grupos Kafka e max.poll (AUD-10)

**Problema:** dois listeners compartilham o grupo `payment-sbus` consumindo tópicos diferentes (rebalance de um revoga o outro) e `max.poll.interval.ms` default (5 min) torna o orçamento de retry de 30 min inalcançável.

**Decisão:** grupos `payment-sbus-requested` e `payment-sbus-core-response`; `max.poll.interval.ms: 2100000` (35 min > 30 min de orçamento) no consumer default do YAML. Grupos novos + `offsetReset: EARLIEST` releem o histórico **uma vez** — seguro por construção: `request_id UNIQUE` torna replay de `Requested` no-op, e resposta para simulação já terminal é ignorada. Um IT prova o replay-inócuo antes do rename (roda o handler duas vezes sobre o mesmo registro e assere estado inalterado).

## 5. Fingerprint no replay do SBUS (AUD-01)

**Problema:** após os 15m da reserva da API, o SBUS resolve replay por chave **sozinha** — payload divergente recebe o resultado da original (valor errado, sem 409).

**Decisão:** migration `V10` adiciona `fingerprint` a `idempotency_record` (nullable — linhas antigas ficam null). `persistRequested` grava o fingerprint (mesmo algoritmo canônico da API, portado para o SBUS — os dois lados calculam do payload, não confiam em header). `findReplayTarget` compara: igual → replay como hoje; divergente ou null-legado → **não é replay**, segue o caminho de simulação nova. Sem canal de 409 assíncrono — semanticamente correto: chave idempotente deduplica operações idênticas; operação diferente processa como nova.

## 6. Readiness real no SBUS (AUD-09)

**Problema:** `readiness-required: true` declarado para 4 dependências, zero `HealthIndicator` implementado; e queda do Registry vira `PoisonMessageException` → pagamento válido dead-letterado.

**Decisão:** um `HealthIndicator` por dependência (Kafka via `AdminClient.describeCluster` com timeout do budget; Postgres via `SELECT 1` com timeout; Redis via `PING`; Registry via `GET /system/info`), cada um lendo `DependencyPolicies` — a config declarada passa a ser executada. Classificação: `SimulationMessageHandler` separa exceção de conectividade do client do Registry (retry) de payload indecodificável (poison) por tipo/causa da exceção do Apicurio client.

## 7. Estado FAILED no async-redis (AUD-13)

`JobState.FAILED` + `JobStatusView.Failed` (switch exaustivo força os call sites). DLQ marca `FAILED` via `SET XX` condicionado a `PROCESSING` (nunca sobrescreve `COMPLETED` de um release que correu junto). `GET` → `200` com `status: "FAILED"`. O `ENQUEUE_FAILED` CAS (AUD-03) usa o mesmo padrão: transições de status viram Lua condicional, eliminando os check-then-act.

## 8. Recalibração (AUD-30, AD-007)

| Parâmetro | Antes (AD-006) | Depois (AD-007) |
| --- | --- | --- |
| Sustentado | 10.000 req/min × 15 min | **1.000 req/min × 15 min** |
| Spike | 20.000 req/min × 60s | **2.000 req/min × 60s** |
| Latência | — | **média ≤ 300ms; p99 ≤ 10s** |
| Admissão rota | 200/s | **20/s** |
| Admissão tenant | 50/s | **10/s** |
| Core | 50/s | 50/s (agora 3× de folga) |

Gate: k6 com 2 tenants (~8,5/s cada), thresholds `avg<300 p(99)<10000` em `http_req_duration`, e o veredito do relatório reprova com `429 > 1%` do steady — o mix de status entra no veredito, não só erro técnico. O relatório de 2026-08-12 é marcado **REVOGADO** no CAP-02 (mediu o limiter de tenant, não capacidade).

## Tech Decisions (registro)

| Decisão | Alternativa rejeitada | Por quê |
| --- | --- | --- |
| Latch em memória p/ kill-switch | Persistir latch em disco local | Estado local por instância contradiz o modelo de flags compartilhadas; o break-glass estático cobre o cold start |
| Lua de 2 contadores | Compensação `DECR` client-side em 2 round-trips | Janela entre os dois comandos re-cria o defeito sob concorrência |
| Grupos novos + releitura idempotente | Manter grupo compartilhado só com max.poll maior | Rebalance cruzado entre tópicos continuaria; a releitura única é comprovadamente inócua |
| Fingerprint calculado no SBUS | Confiar num header de fingerprint da API | O SBUS não pode confiar em atestado de outro processo para decisão de dinheiro |
| `paymentMethod` com `@Pattern` | Só colapso na métrica | Contrato explícito rejeita lixo na borda; métrica limitada é segunda linha |
