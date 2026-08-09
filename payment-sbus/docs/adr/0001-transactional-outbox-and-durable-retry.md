# ADR-0001 — Transactional outbox, retry durável e DLQ recuperável

Status: Accepted
Date: 2026-08-09

## Contexto

O fluxo precisa sobreviver a falhas entre banco e Kafka, redelivery, concorrência entre instâncias e indisponibilidade da DLQ sem finalizar silenciosamente uma solicitação aceita.

## Decisão

Persistir estado e outbox na mesma transação local. Publicar fora da transação após claim limitado. Usar token de ownership e lock de sessão para impedir sends simultâneos após reclaim. Persistir retries com `next_attempt_at` antes de confirmar o registro original. Manter falhas terminais em `DLQ_PENDING` até ack do broker, com backoff, métrica e runbook.

## Alternativas consideradas

1. Transação distribuída Kafka/PostgreSQL: rejeitada por acoplamento operacional e ausência de suporte necessário.
2. Sleep no consumer: rejeitado por head-of-line blocking e processamento prematuro.
3. Marcar `FAILED` antes da DLQ: rejeitado porque transforma indisponibilidade do broker em perda silenciosa.
4. Lease sem fencing/lock de sessão: rejeitado porque dois owners poderiam publicar a mesma claim simultaneamente.

## Consequências

- entrega é at-least-once e consumidores precisam de idempotência;
- crash após ack pode republicar a mesma identidade;
- PostgreSQL é a fonte de recovery do publisher;
- retry/DLQ adicionam filas, retenção, métricas e procedimentos operacionais;
- migrations permanecem append-only.

## Supersession

Nenhum ADR substitui esta decisão. Uma mudança de atomicidade, ownership ou terminalidade exige novo ADR e plano de compatibilidade.
