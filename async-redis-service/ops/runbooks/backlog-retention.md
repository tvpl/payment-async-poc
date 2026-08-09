# Backlog de retenção

1. Verifique `async_stream_length` e o `WARN` de `StreamRetentionMonitor` nos logs (mensagem `backlog is ... at or above the safe budget`).
2. Confirme se `async_pending` (PEL) está crescendo junto — indica workers atrasados, não só volume, e deve acionar o runbook de [outage de worker](worker-outage-recovery.md) em vez deste.
3. Este serviço nunca trima o stream automaticamente (ver [ADR-0001](../../docs/adr/0001-stream-retention-and-wakeup-protocol.md)). Antes de trimar manualmente, confirme que o PEL não tem entradas antigas (`XPENDING <stream> <group>`) — trimar com pendências abertas remove payload não confirmado.
4. Trim seguro manual (Redis 8.2+): `XTRIM <stream> MAXLEN <n> ACKED` (exato, não aproximado — a combinação aproximada com `ACKED` tem bug conhecido upstream). Em versão anterior, não trime; aumente `stream-maxlen`/memória ou drene o backlog primeiro.
5. Escale se o backlog continuar crescendo após confirmar que os workers estão consumindo (`consumingWorkers > 0` em `/health/readiness`).
