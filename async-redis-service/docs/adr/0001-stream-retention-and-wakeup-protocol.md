# ADR-0001 — Retenção PEL-safe e protocolo de release atômico

Status: Accepted
Date: 2026-08-09

## Contexto

O fluxo precisa sobreviver a redelivery, duas ou mais instâncias competindo pelo mesmo consumer group, e Redis indisponível no startup ou no meio do laço — sem nunca remover payload que um consumer group ainda não confirmou, e sem nunca liberar um resultado, status ou wakeup de forma duplicada ou parcial.

## Decisão

**Retenção (RED-03):** o `XADD ... MAXLEN ~` inline foi removido de `JobQueue.enqueue`. Este serviço não trima o stream automaticamente em nenhuma versão de Redis. Um `StreamRetentionMonitor` detecta e reporta se o servidor conectado é novo o bastante (Redis 8.2+) para a política de trim `ACKED` (que só remove entradas confirmadas por todos os consumer groups), mas **não invoca** esse trim: o driver fixado neste build (Lettuce 6.4.0.RELEASE) não tem suporte tipado a `ACKED` em `XTrimArgs`, e não há Redis 8.2+ disponível no ambiente de desenvolvimento/CI atual para verificar a chamada via integração. Emitir um comando raw não testado violaria a mesma garantia que esta decisão protege. O monitor alerta (log `WARN`) quando o backlog atinge `retention-alert-threshold * stream-maxlen`, para que a operação trima manualmente (com o PEL checado) antes de um problema de memória.

**Release atômico (RED-06):** `ResultReleaser` grava resultado, status terminal e wakeup em um único `EVAL` Lua. O status usa `XX KEEPTTL` (nunca ressuscita um job nunca aceito, nunca reseta o próprio TTL). O wakeup é gated por uma chave-marcador (`SET NX`): só a primeira execução bem-sucedida do script empurra a lista de wakeup, então uma redelivery do mesmo release (worker que morreu entre o release e o ACK) nunca duplica a entrada. O worker só confirma (`XACK`) depois que o script retorna sem lançar.

## Alternativas consideradas

1. **Trim `ACKED` via comando raw agora:** rejeitada — não verificável neste ambiente (sem Redis 8.2+, sem suporte tipado no driver); implementar sem gate de teste violaria "nunca inventar segurança de trim".
2. **Manter `MAXLEN ~` inline, só reduzir o valor:** rejeitada — qualquer trim por contagem bruta, aproximado ou exato, pode remover entradas pendentes; reduzir o valor só reduz a janela até o problema aparecer.
3. **Quatro chamadas separadas para resultado/status/wakeup (comportamento anterior):** rejeitada — uma conexão perdida no meio deixa o job com resultado gravado mas status preso em `PROCESSING`, e uma redelivery duplicava a entrada de wakeup a cada nova tentativa.
4. **Transação Redis (`MULTI`/`EXEC`) em vez de Lua:** rejeitada — `MULTI` não permite decisão condicional entre comandos (o `if` que gate o wakeup), e Lua já é o padrão usado pelas outras fronteiras deste workspace (`payment-api` idempotency, `feature-control` flag store).

## Consequências

- o stream cresce sem trim automático até que um operador trima manualmente ou o driver seja atualizado para suportar `ACKED` de fato;
- o alerta de backlog é a única defesa automática contra crescimento não observado — silêncio do alerta não substitui monitorar `async_stream_length`;
- redelivery de um release é sempre segura (idempotente), mas o campo `processedAtEpochMs`/`processedBy` do resultado pode mudar entre tentativas até a primeira que de fato conclui o `EVAL`;
- uma futura atualização do Lettuce com suporte a `ACKED`, ou um Redis de referência 8.2+ disponível em CI, é o gatilho para revisar esta decisão — não uma mudança de código isolada.

## Supersession

Nenhum ADR substitui esta decisão. Habilitar trim automático `ACKED`, ou trocar o protocolo de release atômico, exige novo ADR.
